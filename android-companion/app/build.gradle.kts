plugins {
    id("com.android.application")
}

android {
    namespace = "dev.inkflow.boox"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.inkflow.boox"
        minSdk = 26
        targetSdk = 30
        versionCode = 2
        versionName = "0.1.1-poc"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        jniLibs {
            pickFirsts += "lib/**/libc++_shared.so"
        }
    }
}

dependencies {
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.4")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
