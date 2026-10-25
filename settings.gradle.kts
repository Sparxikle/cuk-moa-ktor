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
// the kr.ac.catholic.cukmoa:shared dependency is linked to the ../cuk-moa-kmp/shared project,
// so if the app and server API shapes diverge, the server fails to compile.
val appRepo = file(providers.gradleProperty("appRepo").getOrElse("../cuk-moa-kmp"))
if (appRepo.resolve("shared").exists()) includeBuild(appRepo)
