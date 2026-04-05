rootProject.name = "SimpleIDE"

include(":app")
include(":native")

buildCache {
    local {
        isEnabled = true
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases/") }
    }
}
