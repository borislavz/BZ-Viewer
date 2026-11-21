pluginManagement {
    repositories {
        maven { url = uri("https://dl.google.com/dl/android/maven2/") }
        maven { url = uri("https://repo.maven.apache.org/maven2/") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://dl.google.com/dl/android/maven2/") }
        maven { url = uri("https://repo.maven.apache.org/maven2/") }
        google()
        mavenCentral()
    }
}

rootProject.name = "BZ View"
include(":app")
