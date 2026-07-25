import com.android.build.api.dsl.ApplicationExtension
import com.cybersensei.buildlogic.BuildConfig
import com.cybersensei.buildlogic.libs
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        // AGP 9 has built-in Kotlin support: applying 'org.jetbrains.kotlin.android' on
        // top of it is now an error.
        pluginManager.apply("com.android.application")

        extensions.configure<ApplicationExtension> {
            compileSdk = BuildConfig.COMPILE_SDK
            compileSdkMinor = BuildConfig.COMPILE_SDK_MINOR

            defaultConfig {
                applicationId = BuildConfig.APPLICATION_ID
                minSdk = BuildConfig.MIN_SDK
                targetSdk = BuildConfig.TARGET_SDK
                versionCode = 1
                versionName = "0.1.0"
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                vectorDrawables.useSupportLibrary = true
            }

            buildTypes {
                debug {
                    applicationIdSuffix = ".debug"
                    versionNameSuffix = "-debug"
                }
                release {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                    // Release builds are signed with the debug key for now so that CI can
                    // produce an installable APK; a real key arrives in Fase 7.
                    signingConfig = signingConfigs.getByName("debug")
                }
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
                isCoreLibraryDesugaringEnabled = true
            }

            packaging {
                resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
            }
        }

        configureKotlinJvmTarget()

        dependencies {
            add("coreLibraryDesugaring", libs.findLibrary("android-desugarJdkLibs").get())
        }
    }
}

internal fun Project.configureKotlinJvmTarget() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}
