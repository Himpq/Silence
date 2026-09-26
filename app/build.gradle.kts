val enableModernLsposed = providers
    .gradleProperty("enableModernLsposed")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cn.himpqblog.silence"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.himpqblog.silence"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/daemon-assets"))
            if (enableModernLsposed.get()) {
                java.srcDir("src/modern/java")
            }
        }
    }
}

// CMake builds the native executable and copies it into the generated asset tree.
// The asset merge must wait for the matching native variant, otherwise a clean build
// can package an empty daemon directory.
afterEvaluate {
    tasks.matching { task ->
        task.name.startsWith("merge") && task.name.endsWith("Assets")
    }.configureEach {
        val variantName = name.removePrefix("merge").removeSuffix("Assets")
        dependsOn(tasks.matching { task ->
            task.name == "externalNativeBuild$variantName" ||
                task.name.startsWith("buildCMake$variantName")
        })
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
