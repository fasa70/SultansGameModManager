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

rootProject.name = "SultansGameModManager"

include(":app")
include(":merge-native")
project(":merge-native").projectDir = file("merge-native")
include(":core:model")
include(":core:storage")
include(":core:merge")
include(":core:workshop")
include(":core:steam-protocol")
include(":core:workshop-download")
include(":core:apk")
include(":core:game-bridge")
include(":core:loader-split")
include(":bootstrap")
project(":bootstrap").projectDir = file("../bootstrap")
