plugins {
    alias(libs.plugins.cybersensei.android.library)
    alias(libs.plugins.cybersensei.hilt)
}

android {
    namespace = "com.cybersensei.academy.core.database"
}

ksp {
    // Exported schemas are the record of every migration; they belong in version control.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(projects.core.curriculum)
    api(projects.core.model)
    api(projects.engine.mastery)
    api(projects.engine.scenario)
    api(projects.engine.scheduler)
    api(projects.engine.tutor)
    implementation(projects.core.common)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
