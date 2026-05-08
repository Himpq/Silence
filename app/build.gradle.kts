val enableModernLsposed = providers
    .gradleProperty("enableModernLsposed")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cn.himpqblog.slience"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.himpqblog.slience"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            if (enableModernLsposed.get()) {
                java.srcDir("src/modern/java")
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    compileOnly("de.robv.android.xposed:api:82")

    if (enableModernLsposed.get()) {
        compileOnly("io.github.libxposed:api:101.0.1")
    }
}
