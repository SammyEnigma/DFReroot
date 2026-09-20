plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.polygraphene.df.installer"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.polygraphene.df.installer"
        minSdk = 32
        targetSdk = 36
        versionCode = 4
        versionName = "2.0.1"
    }
    signingConfigs {
        // Must use the same signing key as DFReroot: the key inserted into
        // packages.xml has to match the DFReroot APK signature.
        create("keystore") {
            storeFile = rootProject.file("app/keystore.jks")
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
