plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.polygraphene.df.reroot"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.polygraphene.df.reroot"
        minSdk = 32
        targetSdk = 36
        versionCode = 4
        versionName = "2.0.1"

        // DirtyFrag native payload (stage1.S) is AArch64-only; the rest of the
        // chain (system_server hosting, network_stack hop) is arch-independent
        // and testable on the x86_64 emulator (native load fails gracefully).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
    signingConfigs {
        create("keystore") {
            storeFile = file("keystore.jks")
            storePassword = "dfreroot"
            keyAlias = "dfreroot"
            keyPassword = "dfreroot"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("keystore")
        }
        release {
            signingConfig = signingConfigs.getByName("keystore")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
