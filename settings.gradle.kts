rootProject.name = "cuk-moa-ktor"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}

// Uses the app repo's shared module (API models) directly. With both repos cloned side by side,
// the kr.ac.catholic.cukmoa:shared dependency is linked to the app repo's shared project,
// so if the app and server API shapes diverge, the server fails to compile.
// Looks for the app repo (cuk-moa-kmp) in the sibling folder
val appRepo = providers.gradleProperty("appRepo").orNull?.let(::file)
    ?: file("../cuk-moa-kmp")
if (appRepo.resolve("shared").exists()) includeBuild(appRepo)
gradle.extra["appRepo"] = appRepo.absolutePath
