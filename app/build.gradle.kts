plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nova.gpspro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nova.gpspro"
        minSdk = 30          // Android 11
        targetSdk = 35       // Android 15+ (runs on 16)
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("en", "ar")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")   // offline QR encode/decode (pure Java)
    testImplementation("junit:junit:4.13.2")
}
