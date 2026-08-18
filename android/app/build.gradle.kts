plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.claudeapprover"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.claudeapprover"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "1.10"
    }

    // 고정된 debug keystore를 사용해서, 매 빌드마다(CI 포함) 같은 서명이 나오도록 한다.
    // 이래야 새 APK를 기존 앱 위에 그냥 덮어 설치할 수 있다 (매번 삭제 후 재설치할 필요 없음).
    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
