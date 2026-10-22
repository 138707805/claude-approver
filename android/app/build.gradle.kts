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
        versionCode = 12
        versionName = "1.11"
    }

    // Use a locally generated Android debug key, never a signing key committed to git.
    // Debug APKs are test builds and cannot safely upgrade an old APK signed by the leaked key.

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
