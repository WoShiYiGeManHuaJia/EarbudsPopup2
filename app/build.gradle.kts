plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.woshiyigemanhuajia.btpopup"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.woshiyigemanhuajia.btpopup"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
        resConfigs("zh", "en")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // CI 会在 build.yml 中提前生成 release.keystore（固定 debug 签名密钥）
            val ks = file(project.parent?.projectDir ?: ".", "release.keystore")
            if (ks.exists()) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = ks
                    storePassword = "android"
                    keyAlias = "release"
                    keyPassword = "android"
                }
            }
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // CI 会在 build.yml 中提前生成 release.keystore（固定 debug 签名密钥）
            val ks = file(project.parent?.projectDir ?: ".", "release.keystore")
            if (ks.exists()) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = ks
                    storePassword = "android"
                    keyAlias = "release"
                    keyPassword = "android"
                }
            } else {
                // 本地开发/无 keystore 时复用 debug 签名
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
