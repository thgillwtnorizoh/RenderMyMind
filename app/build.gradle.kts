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
        versionCode = 5
        versionName = "0.3.1-alpha"
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
    // Bundled Latin model: available immediately, no first-run model download during a session.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
