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
        versionCode = 15
        versionName = "0.5.0-alpha"
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
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
