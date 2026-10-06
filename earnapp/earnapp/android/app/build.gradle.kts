plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ro.earnapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "ro.earnapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // ID de test Google pentru reclame recompensate. Înlocuiește-l cu ID-ul tău din AdMob la publicare.
        buildConfigField("String", "REWARDED_AD_UNIT", "\"ca-app-pub-3940256099942544/5224354917\"")
    }

    buildTypes {
        debug {
            // 10.0.2.2 = calculatorul tău, văzut din emulator
            buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:3000\"")
        }
        release {
            // Pune aici adresa serverului tău (HTTPS obligatoriu)
            buildConfigField("String", "BASE_URL", "\"https://serverul-tau.example.com\"")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.gms:play-services-ads:23.4.0")
}
