package com.cybersensei.buildlogic

import java.util.Properties
import org.gradle.api.Project

/**
 * Where the release key lives, and how it is found.
 *
 * The key itself is never in the repository. Module 2.9 of the course spends a page on why:
 * lose it and this app can never be updated again, leak it and someone else's build installs
 * over yours as though it were yours. So the build looks for it in two places that are both
 * outside version control — a `keystore.properties` file next to the project, or four
 * environment variables for a machine that builds without a file — and if it finds neither
 * it says so and falls back to the debug key rather than failing.
 */
data class ReleaseSigning(
    val storeFile: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
) {
    companion object {
        fun find(project: Project): ReleaseSigning? =
            fromFile(project) ?: fromEnvironment()

        private fun fromFile(project: Project): ReleaseSigning? {
            val file = project.rootProject.file("keystore.properties")
            if (!file.exists()) return null
            val properties = Properties().apply {
                file.inputStream().use { load(it) }
            }
            return of(
                storeFile = properties.getProperty("storeFile"),
                storePassword = properties.getProperty("storePassword"),
                keyAlias = properties.getProperty("keyAlias"),
                keyPassword = properties.getProperty("keyPassword"),
            )
        }

        private fun fromEnvironment(): ReleaseSigning? = of(
            storeFile = System.getenv("CYBERSENSEI_STORE_FILE"),
            storePassword = System.getenv("CYBERSENSEI_STORE_PASSWORD"),
            keyAlias = System.getenv("CYBERSENSEI_KEY_ALIAS"),
            keyPassword = System.getenv("CYBERSENSEI_KEY_PASSWORD"),
        )

        /** All four or nothing: a half-configured key produces a confusing build failure. */
        private fun of(
            storeFile: String?,
            storePassword: String?,
            keyAlias: String?,
            keyPassword: String?,
        ): ReleaseSigning? {
            if (storeFile.isNullOrBlank() ||
                storePassword.isNullOrBlank() ||
                keyAlias.isNullOrBlank() ||
                keyPassword.isNullOrBlank()
            ) {
                return null
            }
            return ReleaseSigning(storeFile, storePassword, keyAlias, keyPassword)
        }
    }
}
