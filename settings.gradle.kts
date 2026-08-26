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

rootProject.name = "othello"

include(":app")
include(":core:game")
include(":core:network")
include(":core:auth")
include(":core:designsystem")
include(":feature:matchmaking")
include(":feature:match")
include(":feature:records")
include(":feature:review")
include(":feature:theory")
include(":feature:profile")
include(":feature:rating")
include(":feature:research")
include(":analysis:api")
include(":analysis:edax")
include(":transport:webrtc")
include(":data:supabase")
