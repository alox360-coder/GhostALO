plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// La chiave di firma NON è nel repository: arriva da variabili d'ambiente
// (GitHub Secrets in CI). Senza chiave, la build usa la chiave di prova di Android.
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_FILE")
val hasReleaseKey: Boolean = keystorePath != null && File(keystorePath).exists()

android {
    namespace = "it.privacycheck"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.privacycheck"
        minSdk = 30
        targetSdk = 34
        // Il numero di build di GitHub garantisce versionCode sempre crescente.
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "5.2"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = File(keystorePath!!)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
