plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.eser.belgetarayici"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eser.belgetarayici"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    // Google ML Kit Belge Tarayici motoru: otomatik kenar bulma, perspektif
    // duzeltme, golge/leke temizleme, yazi netlestirme + JPEG & PDF ciktisi.
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    // Resimden yazi cikarma (OCR).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Metnin dilini otomatik tanima.
    implementation("com.google.mlkit:language-id:17.0.6")
    // Cihazda (cevrimdisi) ceviri - 50+ dil.
    implementation("com.google.mlkit:translate:17.0.3")
}
