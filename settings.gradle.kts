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
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "HomeworkBuddy"
include(":app", ":scanner-core", ":scanner-processing-opencv", ":scanner-camera", ":scanner-ui-compose", ":scanner-export")
listOf("scanner-core", "scanner-processing-opencv", "scanner-camera", "scanner-ui-compose", "scanner-export").forEach {
    project(":$it").projectDir = file("third_party/offline-doc-scanner/$it")
}
