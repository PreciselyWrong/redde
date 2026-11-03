buildscript {
    val jvmOnly = providers.gradleProperty("redde.jvmOnly").orNull == "true"
    repositories {
        if (!jvmOnly) google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.kotlin.serialization.plugin)
        if (!jvmOnly) {
            classpath(libs.android.gradle.plugin)
            classpath(libs.compose.compiler.plugin)
            // org.jetbrains.kotlin.android comes with kotlin-gradle-plugin above.
        }
    }
}

subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}
