plugins {
    alias(libs.plugins.cybersensei.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// The logs and the exercises are content, like every other thing the student reads.
sourceSets {
    named("main") {
        resources.srcDir(rootProject.file("content"))
        resources.setIncludes(listOf("regole/**"))
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
