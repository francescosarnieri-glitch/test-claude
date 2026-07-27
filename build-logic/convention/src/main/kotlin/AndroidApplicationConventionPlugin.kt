import com.android.build.api.dsl.ApplicationExtension
import com.cybersensei.buildlogic.BuildConfig
import com.cybersensei.buildlogic.ReleaseSigning
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
                versionName = "1.0.0"
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                vectorDrawables.useSupportLibrary = true
            }

            val releaseKey = ReleaseSigning.find(target)
            if (releaseKey != null) {
                signingConfigs.create("release") {
                    storeFile = file(releaseKey.storeFile)
                    storePassword = releaseKey.storePassword
                    keyAlias = releaseKey.keyAlias
                    keyPassword = releaseKey.keyPassword
                    // v2 and v3 only. v1 (the old JAR signature) buys nothing here: it
                    // matters below Android 7, and this app already starts at 7.
                    enableV1Signing = false
                    enableV2Signing = true
                    enableV3Signing = true
                }
            } else {
                logger.lifecycle(
                    "Nessuna chiave di firma trovata (keystore.properties o variabili " +
                        "CYBERSENSEI_*): la release verrà firmata con la chiave di debug.",
                )
            }

            buildTypes {
                debug {
                    applicationIdSuffix = ".debug"
                    versionNameSuffix = "-debug"
                    // Un APK di debug non è solo per il debug: è il modo in cui questa app
                    // arriva in mano a chi la prova, e deve installarsi sopra quella che ha
                    // già senza cancellargli i dati — cosa che la release, con un altro
                    // applicationId, non può fare. Senza R8 porta con sé una sessantina di
                    // megabyte di codice non usato, e i canali con cui si spedisce un file
                    // hanno un limite. Spento di default: rallenta ogni build e offusca le
                    // tracce di errore, che è esattamente ciò che serve durante lo sviluppo.
                    isMinifyEnabled = project.providers
                        .gradleProperty("cybersensei.debug.compatto").isPresent
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                        // Toglie il codice inutilizzato senza rinominare niente: vedi il file.
                        "proguard-debug.pro",
                    )
                }
                release {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                    // The real key when there is one, the debug key otherwise: a build that
                    // cannot be installed is worse than a build signed by the wrong hand, and
                    // an APK signed with the debug key is obvious to anyone who checks.
                    signingConfig = signingConfigs.findByName("release")
                        ?: signingConfigs.getByName("debug")
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
