plugins {
    id("com.android.application")
}

android {
    namespace = "org.fellow99.ptyprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.fellow99.ptyprobe"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        // 探针 app 不发布，版本即对应 .so 的 node 版本便于对应（Release v22.23.4）
        versionName = (providers.gradleProperty("PROBE_APP_VERSION").orNull ?: "0.1.0-probe")
        // 仅 arm64：跟随 runtime/ci 矩阵（A3 ABI 纪律）
        ndk { abiFilters += "arm64-v8a" }
    }

    // 载荷需物理落盘 nativeLibraryDir（PRB-01 存在性判据；同 aosp-node-cli probe-app 口径）
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
        }
    }
}

dependencies {
    // 配对 runtime（C5 同版本配对 / pty.lock.json matrix 纪律）：
    // addon = node 22.23.4（NODE_MODULE_VERSION 127，Release v22.23.4 投放，probe 常量 ARTIFACT_NODE）
    // ⇄ node-runtime:22.23.4（libnode.so + libc++_shared.so）。
    // 用 runtimeOnly：编译期不依赖引擎符号，仅打包进 APK——PRB-02 System.loadLibrary("node")
    // 预载 + PRB-03 System.loadLibrary("pty_node") 解析 DT_NEEDED libnode.so 均需 libnode.so 在场。
    runtimeOnly("io.github.fellow99:node-runtime:22.23.4")
}
