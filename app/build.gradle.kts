plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val qstarKeystorePath = System.getenv("QSTAR_KEYSTORE_FILE")
val qstarStorePassword = System.getenv("QSTAR_STORE_PASSWORD")
val qstarKeyAlias = System.getenv("QSTAR_KEY_ALIAS")
val qstarKeyPassword = System.getenv("QSTAR_KEY_PASSWORD")
val hasStableSigning = listOf(qstarKeystorePath, qstarStorePassword, qstarKeyAlias, qstarKeyPassword).all { !it.isNullOrBlank() }
val qstarDebugKeystorePath = System.getenv("QSTAR_DEBUG_KEYSTORE_FILE")
    ?: "${System.getProperty("user.home")}/.android/debug.keystore"

android {
    namespace = "ua.grey.qstarlight"
    compileSdk = 35
    defaultConfig {
        applicationId = "ua.grey.qstarlight"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.5.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file(qstarDebugKeystorePath)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasStableSigning) {
            create("qstarStable") {
                storeFile = rootProject.file(qstarKeystorePath!!)
                storePassword = qstarStorePassword
                keyAlias = qstarKeyAlias
                keyPassword = qstarKeyPassword
            }
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            if (hasStableSigning) signingConfig = signingConfigs.getByName("qstarStable")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    testImplementation("junit:junit:4.13.2")
}
