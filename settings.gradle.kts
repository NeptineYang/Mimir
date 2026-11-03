// 工程入口：仓库声明与模块包含。
// 仓库列表固化在此，不依赖 ~/.gradle/init.gradle.kts：
// 换掉 GRADLE_USER_HOME 后镜像仍然生效，依赖解析不受本机全局配置影响。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

rootProject.name = "Mimir"
include(":app")
// 隐藏 API 编译期桩：只进编译期 classpath（app 用 compileOnly 引用），不进 APK。
// 业务分层仍全部在 app 的单模块包边界内，此模块只承载 framework 的隐藏类型声明。
include(":hidden-api")
