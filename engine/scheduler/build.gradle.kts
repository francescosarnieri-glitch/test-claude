plugins {
    alias(libs.plugins.cybersensei.jvm.library)
}

dependencies {
    implementation(projects.core.common)
    api(projects.core.model)
    api(projects.engine.mastery)

    testImplementation(libs.junit)
}
