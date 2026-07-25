import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "com.cybersensei.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // Only the plugins whose *API* these convention plugins actually touch belong here.
    // KSP, Hilt and the Compose compiler are applied by id (their jars are on the root
    // build's classpath), and adding them here would break the build: this module is
    // compiled by the Kotlin version embedded in Gradle, which is older than the metadata
    // of those plugin jars.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "cybersensei.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "cybersensei.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "cybersensei.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("hilt") {
            id = "cybersensei.hilt"
            implementationClass = "HiltConventionPlugin"
        }
        register("jvmLibrary") {
            id = "cybersensei.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
