plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.petertroll.pdfcraftmobile"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.petertroll.pdfcraftmobile"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "0.8.0-touch-3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true; buildConfig = true }
    lint { disable += "GradleDependency" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}
