plugins {
    alias(libs.plugins.cybersensei.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// The syllabus is content, like the professor's script: JSON, outside the code.
sourceSets {
    named("main") {
        resources.srcDir(rootProject.file("content"))
        resources.setIncludes(listOf("curriculum/**"))
    }
}

dependencies {
    api(projects.core.model)

    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
