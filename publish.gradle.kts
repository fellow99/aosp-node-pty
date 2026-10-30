// publish.gradle.kts —— 发布面共享配置（由 node-pty 的 build.gradle.kts apply）。
//
// 直接对齐 aosp-node-runtime/publish.gradle.kts 的发布口径（同插件、同纪律），差异仅：
//   - 坐标 artifactId = node-pty；groupId 固定 io.github.fellow99
//   - 版本源唯一为 PTY_VERSION（= 配对 Node 版本；不能用 NODE_ 前缀，Gradle 9.4 保留）
//   - POM SCM url 补丁指向 aosp-node-pty 仓
//
// 插件：com.vanniktech:gradle-maven-publish-plugin 0.37.0
//   经 settings.gradle.kts 的 pluginManagement 加载（id=com.vanniktech.maven.publish.base）。
//
// 纪律：
//   - 敏感值（Portal Token / GPG）只从用户级 ~/.gradle/gradle.properties 读取，绝不入库
//   - RELEASE=false（默认）时 Central/签名任务被禁用，防误发

println("[publish] 插件经 settings pluginManagement 加载（id=com.vanniktech.maven.publish.base）")

apply(plugin = "signing")

// ── 求值期配置（AGP 读 singleVariants / coordinates 定值之前必须完成）──────────
val mavenPublishingExt = extensions.getByName("mavenPublishing")
fun mp(name: String, vararg args: Any?) {
    val m = (mavenPublishingExt as Any).javaClass.methods
        .first { it.name == name && it.parameterCount == args.size }
    try {
        m.invoke(mavenPublishingExt, args)
    } catch (e: java.lang.reflect.InvocationTargetException) {
        throw e.targetException
    } catch (e: IllegalArgumentException) {
        m.invoke(mavenPublishingExt, *args)
    }
}
// 坐标：groupId 固定 io.github.fellow99；artifactId = 模块名（node-pty）。
// version：pty 的坐标 version 唯一取 PTY_VERSION（= Node 版本）；模块 build.gradle.kts
// 另以 project.version + publication 反射覆写，双保险。
val artifactId = project.name
val versionName = providers.gradleProperty("PTY_VERSION").orNull
    ?: error("publish.gradle.kts 需要 PTY_VERSION（= 配对 Node 版本）；当前未设置")
mp("coordinates", "io.github.fellow99", artifactId, versionName)
mp("pomFromGradleProperties")

// 发布目标：Central Portal（0.37.0 起直连 central.sonatype.com）。
// (requiresToken=false, validation=NONE)：配置期不强制 Token、上传后人工在 Portal 点 Publish。
// -PAUTOMATIC_RELEASE=true 时 validation=PUBLISHED（校验通过自动发布并等待上线）。
val dvClass = Class.forName("com.vanniktech.maven.publish.DeploymentValidation", true, project.buildscript.classLoader)
val centralValidation = if (providers.gradleProperty("AUTOMATIC_RELEASE").orNull?.toBoolean() == true)
    dvClass.getDeclaredField("PUBLISHED").get(null) else dvClass.getDeclaredField("NONE").get(null)
val ptmcMethod = mavenPublishingExt.javaClass.methods
    .first { it.name == "publishToMavenCentral" && it.parameterCount == 2
        && it.parameterTypes[0] == java.lang.Boolean.TYPE
        && it.parameterTypes[1] == dvClass }
try {
    ptmcMethod.invoke(mavenPublishingExt, java.lang.Boolean.FALSE, centralValidation)
} catch (e: java.lang.reflect.InvocationTargetException) {
    throw e.targetException
}
mp("signAllPublications")
// 单 variant：release + sources jar（无 Javadoc）。
val svClass = Class.forName("com.vanniktech.maven.publish.AndroidSingleVariantLibrary", true, project.buildscript.classLoader)
val javadocJarNone: Any = Class.forName("com.vanniktech.maven.publish.JavadocJar\$None", true, project.buildscript.classLoader)
    .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
val sourcesJar: Any = Class.forName("com.vanniktech.maven.publish.SourcesJar\$Sources", true, project.buildscript.classLoader)
    .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
val svCtor = svClass.getConstructor(
    Class.forName("com.vanniktech.maven.publish.JavadocJar", true, project.buildscript.classLoader),
    Class.forName("com.vanniktech.maven.publish.SourcesJar", true, project.buildscript.classLoader),
    String::class.java)
val singleVariant = svCtor.newInstance(javadocJarNone, sourcesJar, "release")
mp("configure", singleVariant)

val releaseEnabled = providers.gradleProperty("RELEASE").orNull?.toBoolean() == true
val portalTokenReady = providers.gradleProperty("mavenCentralUsername").orNull?.isNotBlank() == true &&
    providers.gradleProperty("mavenCentralPassword").orNull?.isNotBlank() == true

afterEvaluate {
    // ── 签名（Maven Central 强制；凭据在用户级 gradle.properties）──
    val keyId = providers.gradleProperty("signing.keyId").orNull
    val keyPass = providers.gradleProperty("signing.password").orNull
    val keyData = providers.gradleProperty("signing.keyData").orNull
    val keyRing = providers.gradleProperty("signing.secretKeyRingFile").orNull
    val s = extensions.getByName("signing") as Any
    val sm = s.javaClass.methods
    sm.first { it.name == "setRequired" && it.parameterTypes[0] == java.lang.Boolean.TYPE }
        .invoke(s, releaseEnabled && portalTokenReady)
    if (!keyData.isNullOrBlank()) {
        sm.first { it.name == "useInMemoryPgpKeys" && it.parameterCount == 3 }
            .invoke(s, keyId, keyData, keyPass)
    } else if (!keyRing.isNullOrBlank()) {
        val tmp = java.nio.file.Files.createTempFile("aosp-pty-sec", ".asc").toFile()
        try {
            val passphraseFile = java.nio.file.Files.createTempFile("aosp-pty-pass", ".txt").toFile()
            passphraseFile.writeText(keyPass ?: "")
            try {
                val proc = ProcessBuilder(
                    "gpg", "--batch", "--yes",
                    "--pinentry-mode", "loopback",
                    "--passphrase-file", passphraseFile.absolutePath,
                    "--armor", "--export-secret-keys", keyId ?: "default-key",
                ).redirectOutput(tmp).redirectErrorStream(true).start()
                if (proc.waitFor() != 0 || !tmp.isFile() || tmp.length() == 0L) {
                    error("publish.gradle.kts: gpg 导出私钥失败（secretKeyRingFile=$keyRing 仅作参考，导出按 keyId=$keyId）")
                }
                val exported = tmp.readText()
                sm.first { it.name == "useInMemoryPgpKeys" && it.parameterCount == 3 }
                    .invoke(s, keyId, exported, keyPass)
            } finally {
                passphraseFile.delete()
            }
        } finally {
            tmp.delete()
        }
    }
}

// ── POM SCM url 补丁（Central Portal 强制要求 <scm><url>）────────────────────
tasks.configureEach {
    if (name.startsWith("generatePomFile")) {
        doLast {
            val realPom = layout.buildDirectory.file("publications/maven/pom-default.xml").get().asFile
            if (realPom.exists()) {
                var xml = realPom.readText()
                if ("<scm>" in xml && "<url>" !in xml.substring(xml.indexOf("<scm>"), xml.indexOf("</scm>"))) {
                    val urlTag = "    <url>https://github.com/fellow99/aosp-node-pty</url>\n  </scm>"
                    xml = xml.replaceFirst("</scm>", urlTag)
                    realPom.writeText(xml)
                    println("[publish] POM SCM url 已补: $realPom")
                }
            }
        }
    }
}

// ── 发布门禁：RELEASE=false 或缺 Token 时禁用 Central/签名任务 ────────────────
tasks.configureEach {
    if (name.startsWith("signMaven") || name == "publishToMavenCentral"
        || name.contains("ToMavenCentral")) {
        enabled = enabled && releaseEnabled && portalTokenReady
    }
}
