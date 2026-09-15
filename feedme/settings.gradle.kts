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

rootProject.name = "FeedMe"
include(":shared:core", ":shared:contracts", ":shared:transport", ":shared:storage", ":shared:sync", ":shared:kitchen", ":shared:session", ":shared:app", ":apps:android", ":server")
include(":shared:planning")
include(":shared:mealflow")
