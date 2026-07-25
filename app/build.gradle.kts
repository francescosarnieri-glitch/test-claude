plugins {
    alias(libs.plugins.cybersensei.android.application)
    alias(libs.plugins.cybersensei.android.compose)
    alias(libs.plugins.cybersensei.hilt)
}

android {
    namespace = "com.cybersensei.academy"

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric needs the real resources to launch the activity for the smoke test.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.curriculum)
    implementation(projects.core.database)
    implementation(projects.core.model)
    implementation(projects.core.ui)
    implementation(projects.engine.mastery)
    implementation(projects.engine.nlu)
    implementation(projects.engine.scheduler)
    implementation(projects.engine.tutor)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
