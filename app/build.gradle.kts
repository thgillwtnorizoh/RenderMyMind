plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.rhythmtracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.rhythmtracker"
        minSdk = 29
        targetSdk = 36
        versionCode = 8
        versionName = "0.4.0-alpha"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // Bundled models: the result parser must work offline and without a first-run model download.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
}
