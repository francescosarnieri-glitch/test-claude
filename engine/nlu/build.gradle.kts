plugins {
    alias(libs.plugins.cybersensei.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// Same rule as the dialogue engine: the questions and answers are content, and they live
// outside the code.
sourceSets {
    named("main") {
        resources.srcDir(rootProject.file("content"))
        // See engine:tutor — each module packages only the content it owns.
        resources.setIncludes(listOf("faq/**"))
    }
}

dependencies {
    implementation(projects.core.common)

    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
