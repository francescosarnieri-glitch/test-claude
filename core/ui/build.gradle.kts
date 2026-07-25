plugins {
    alias(libs.plugins.cybersensei.android.library)
    alias(libs.plugins.cybersensei.android.compose)
}

android {
    namespace = "com.cybersensei.academy.core.ui"
}

dependencies {
    api(projects.core.model)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.material.icons.extended)

    testImplementation(libs.junit)
}
