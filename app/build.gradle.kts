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
        versionCode = 1
        versionName = "0.1.0"
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
