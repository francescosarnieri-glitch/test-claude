plugins {
    alias(libs.plugins.cybersensei.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// The scenario is content too: a branching script, not a state machine written in Kotlin.
sourceSets {
    named("main") {
        resources.srcDir(rootProject.file("content"))
        resources.setIncludes(listOf("scenari/**"))
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
