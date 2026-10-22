import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * The PicPiste signing key, from environment variables (CI) or ~/.picpiste/signing.properties
 * (developer machine). With it, every build (local or CI, debug or release) installs over
 * the previous one. Without it, builds fall back to the machine's debug key.
 */
val signing: Map<String, String> = run {
    val keys = listOf("PICPISTE_KEYSTORE", "PICPISTE_KEYSTORE_PASSWORD", "PICPISTE_KEY_ALIAS", "PICPISTE_KEY_PASSWORD")
    val file = File(System.getProperty("user.home"), ".picpiste/signing.properties")
    val fromFile = Properties().apply { if (file.isFile) file.inputStream().use { load(it) } }
    keys.mapNotNull { k -> (System.getenv(k) ?: fromFile.getProperty(k))?.let { k to it } }.toMap()
}
val hasSigningKey = signing.size == 4 && File(signing.getValue("PICPISTE_KEYSTORE")).isFile

android {
    namespace = "fr.cvvm.picpiste"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "fr.cvvm.picpiste"
        minSdk = 33
        targetSdk = 36
        // CI passes the tag (v0.2 → 0.2) and the run number.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1-dev"
    }

    signingConfigs {
        if (hasSigningKey) {
            create("picpiste") {
                storeFile = File(signing.getValue("PICPISTE_KEYSTORE"))
                storePassword = signing.getValue("PICPISTE_KEYSTORE_PASSWORD")
                keyAlias = signing.getValue("PICPISTE_KEY_ALIAS")
                keyPassword = signing.getValue("PICPISTE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        val key = if (hasSigningKey) signingConfigs.getByName("picpiste") else signingConfigs.getByName("debug")
        debug {
            signingConfig = key
        }
        release {
            isMinifyEnabled = false
            signingConfig = key
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)
    implementation(libs.androidx.work.runtime)
}
