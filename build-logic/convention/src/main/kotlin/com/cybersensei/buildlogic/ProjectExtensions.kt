package com.cybersensei.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** Values shared by every Android module of the app. */
object BuildConfig {
    const val COMPILE_SDK = 36
    const val TARGET_SDK = 36

    /** Android 7.0 — covers well over 95% of active devices while keeping modern APIs. */
    const val MIN_SDK = 24

    const val NAMESPACE_PREFIX = "com.cybersensei.academy"
    const val APPLICATION_ID = "com.cybersensei.academy"
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")
