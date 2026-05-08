pluginManagement {
    repositories {
        google()
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenLocal()
        maven("https://api.xposed.info/")
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "Slience"
include(":app")
