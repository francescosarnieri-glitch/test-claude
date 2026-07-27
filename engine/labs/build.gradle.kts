plugins {
    alias(libs.plugins.cybersensei.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// The exercises that are made of data rather than code live in content, like everything else.
sourceSets {
    named("main") {
        resources.srcDir(rootProject.file("content"))
        resources.setIncludes(listOf("laboratori/**"))
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
