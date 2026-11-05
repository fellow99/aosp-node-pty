pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
// 101-probe-app：独立探针载体工程（不并入 aosp-node-pty root settings——000 单模块红线不动，同
// aosp-node-cli/probe-app 策略）。rootProject.name 与父工程区分。
rootProject.name = "pty-probe-app"
include(":app")
