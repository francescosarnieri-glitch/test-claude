package com.cybersensei.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** Values shared by every Android module of the app. */
object BuildConfig {
    /**
     * Compiled against the newest APIs (current AndroidX requires it), while [TARGET_SDK]
     * stays one behind: raising targetSdk opts the app into new runtime behaviour and is a
     * decision to take deliberately, not a side effect of a dependency bump.
     */
    const val COMPILE_SDK = 37
    const val COMPILE_SDK_MINOR = 1
    const val TARGET_SDK = 36

    /** Android 7.0 — covers well over 95% of active devices while keeping modern APIs. */
    const val MIN_SDK = 24

    const val NAMESPACE_PREFIX = "com.cybersensei.academy"
    const val APPLICATION_ID = "com.cybersensei.academy"
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")
