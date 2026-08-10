plugins {
    id("com.android.application")
}

android {
    namespace = "dev.einkstudio.poc"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.einkstudio.poc"
        minSdk = 26
        targetSdk = 30
        versionCode = 1
        versionName = "0.1.0-poc"

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
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/ASL2.0"
            )
        }
    }
}

dependencies {
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.4")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
