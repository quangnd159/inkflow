plugins {
    id("com.android.application")
}

android {
    namespace = "dev.inkflow.boox"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.inkflow.boox"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-poc"
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
}
