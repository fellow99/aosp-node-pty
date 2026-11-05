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
    // 无外部依赖：载荷即 app/src/main/jniLibs/arm64-v8a/libpty_node.so
    // （不捆绑 libnode.so / libc++_shared.so——配对 runtime 供给件，C2 纪律）
}
