import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
java { targetCompatibility = JavaVersion.VERSION_21 }

application {
    mainClass = "kr.ac.catholic.cukmoa.server.ApplicationKt"
}

// The sample catalog source is in the app repo's shared/data. It's put on the server classpath as is.
val appRepo = file(gradle.extra["appRepo"] as String) // App repo found in settings.gradle.kts
sourceSets.main { resources.srcDir(appRepo.resolve("shared/data")) }

dependencies {
    implementation(libs.cuk.moa.shared)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.jsoup)
    compileOnly(libs.jspecify) // jsoup's null annotations
    implementation(libs.sqlite.jdbc)
    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.kotlin.test)
}

tasks.test { useJUnitPlatform() }

// Single jar for server deployment (used by the Dockerfile)
tasks.register<Jar>("fatJar") {
    group = "build"
    archiveFileName = "server-all.jar"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = application.mainClass }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
