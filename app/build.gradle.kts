plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 固定签名密钥库位于仓库根目录（由 CI 检出后即可用）
val releaseKeystoreFile: File = file("${project.rootDir}/release.keystore")

android {
    namespace = "com.woshiyigemanhuajia.btpopup"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.woshiyigemanhuajia.btpopup"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.1"
        resConfigs("zh", "en")
    }

    // 统一签名配置：debug 与 release 使用同一把固定密钥，
    // 密钥由 CI 从仓库根目录的 release.keystore 读取（见 .github/workflows/build.yml）。
    signingConfigs {
        if (releaseKeystoreFile.exists()) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = "android"
                keyAlias = "release"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // 有固定密钥则用固定密钥；否则退回 debug 签名（本地无密钥时的兜底）
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
