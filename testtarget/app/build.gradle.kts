plugins {
    id("com.android.application")
}

android {
    namespace = "com.mobiletools.systemhelper"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "com.mobiletools.systemhelper"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "3.0.1-test"
    }
    signingConfigs {
        create("testKey") {
            storeFile = rootProject.file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("testKey")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("testKey")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
