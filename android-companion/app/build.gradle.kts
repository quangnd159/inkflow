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
        versionCode = 3
        versionName = "0.2.0"

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
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
