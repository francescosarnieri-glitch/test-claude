import com.android.build.api.dsl.LibraryExtension
import com.cybersensei.buildlogic.BuildConfig
import com.cybersensei.buildlogic.libs
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        // AGP 9 has built-in Kotlin support: applying 'org.jetbrains.kotlin.android' on
        // top of it is now an error.
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            compileSdk = BuildConfig.COMPILE_SDK
            compileSdkMinor = BuildConfig.COMPILE_SDK_MINOR

            defaultConfig {
                minSdk = BuildConfig.MIN_SDK
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
                isCoreLibraryDesugaringEnabled = true
            }

            testOptions.targetSdk = BuildConfig.TARGET_SDK
        }

        configureKotlinJvmTarget()

        dependencies {
            add("coreLibraryDesugaring", libs.findLibrary("android-desugarJdkLibs").get())
        }
    }
}
