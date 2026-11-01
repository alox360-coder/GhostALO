plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// La chiave di firma NON è nel repository: arriva da variabili d'ambiente
// (GitHub Secrets in CI). Senza chiave, la build usa la chiave di prova di Android.
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_FILE")
val hasReleaseKey: Boolean = keystorePath != null && File(keystorePath).exists()

// Versione dell'app: l'UNICO posto da cambiare a ogni release.
// Il versionCode ne deriva (1.0 -> 10000, 1.2 -> 10200, 1.2.3 -> 10203) ed è lo stesso
// per GitHub, F-Droid e build locali, così gli aggiornamenti restano coerenti.
val appVersion = "1.0"
val appVersionCode: Int = appVersion.split(".").map { it.toInt() }.let { p ->
    p[0] * 10000 + (p.getOrNull(1) ?: 0) * 100 + (p.getOrNull(2) ?: 0)
}

android {
    namespace = "it.privacycheck"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.privacycheck"
        minSdk = 30
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
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
            // R8: riduce e ribattezza il codice. Il file mapping.txt (nella release) serve a
            // rileggere i report di errore.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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

dependencies {
    testImplementation("junit:junit:4.13.2")
}
