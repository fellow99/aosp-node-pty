// probe-app —— 101-probe-app 设备回程探针载体（specs/101-probe-app）
// 独立 Gradle 工程（不在 aosp-node-pty settings.gradle 内——000 的单模块红线不动）。
// 载荷：app/src/main/jniLibs/arm64-v8a/libpty_node.so（Release v22.23.4 投放，git 忽略）。
plugins {
    id("com.android.application") version "9.2.1" apply false
}
