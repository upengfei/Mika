pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2") {
            content {
                includeModule("org.jetbrains.kotlin", "kotlin-compiler-embeddable")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2") {
            content {
                includeModule("org.jetbrains.kotlin", "kotlin-compiler-embeddable")
            }
        }
        mavenCentral()
    }
}
rootProject.name = "Mika"
include(":core", ":app")
