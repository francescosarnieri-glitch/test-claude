plugins {
    alias(libs.plugins.cybersensei.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// The professor's lines are content, not code: they live in `content/` at the root of the
// repository and are packaged as plain classpath resources, so they can be rewritten
// without recompiling a single Kotlin file.
sourceSets {
    named("main") {
        resources.srcDir(rootProject.file("content"))
    }
}

dependencies {
    api(projects.core.common)
    api(projects.core.model)
    api(projects.engine.mastery)

    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
