pluginManagement {
    repositories {
        // Aliyun mirrors first on local machines: the upstreams are effectively
        // unreachable from here, so listing them first would pay a network
        // timeout per artifact. GitHub runners (CI=true) use the upstreams.
        if (System.getenv("CI") != "true") {
            maven(url = "https://maven.aliyun.com/repository/google")
            maven(url = "https://maven.aliyun.com/repository/central")
            maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") != "true") {
            maven(url = "https://maven.aliyun.com/repository/google")
            maven(url = "https://maven.aliyun.com/repository/central")
        }
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "HomeworkBuddy"
include(":app", ":offlineScanner")
