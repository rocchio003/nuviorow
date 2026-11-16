plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nuviorows"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nuviorows"
        minSdk = 26
        targetSdk = 34
        // il numero cresce a ogni build su GitHub, così Android accetta l'aggiornamento
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.0.$run"
    }

    // Chiave fissa: tutte le build sono firmate allo stesso modo e si installano come aggiornamento.
    signingConfigs {
        getByName("debug") {
            val ks = rootProject.file("debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation(project(":api"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    implementation("androidx.work:work-runtime:2.9.0")
}
