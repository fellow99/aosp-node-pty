plugins {
    alias(libs.plugins.android.library)
    id("com.vanniktech.maven.publish.base")
}

// node-pty：只装 node-pty 原生件（libpty_node.so，arm64-v8a）。
// 版本号 = 配对 Node 版本号（由 pty.lock.json 锁定，恒等于 aosp-node-runtime 版本）。
// 二进制不入库：src/main/jniLibs/ 由 tools/provision.mjs 从 CI 产物投放。
// 本模块不含任何 Kotlin/Java 源——它只是 AAR 容器。

apply(from = rootProject.file("publish.gradle.kts"))

// 不能用 NODE_* 前缀：Gradle 9.4 把所有 NODE_ 前缀属性保留给内置 Node 工具链。
// 多版本发布：gradle publishToMavenCentral -PPTY_VERSION=24.22.1 -PRELEASE=true
version = (providers.gradleProperty("PTY_VERSION").orNull
    ?: error("node-pty 需要 PTY_VERSION（= 配对 Node 版本）；当前未设置"))

afterEvaluate {
    val publishing = extensions.getByType(org.gradle.api.publish.PublishingExtension::class.java)
    publishing.publications.forEach { pub ->
        (pub as Any).javaClass.methods
            .first { it.name == "setVersion" && it.parameterCount == 1 }
            .invoke(pub, version.toString())
    }
}

android {
    namespace = "org.fellow99.nodepty"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    packaging {
        jniLibs {
            // 未压缩库 + 只读 nativeLibraryDir：唯一合规执行位（SELinux 禁止从可写数据目录执行）。
            useLegacyPackaging = false
        }
    }
}
