plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.example.securesmsforwarder"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.securesmsforwarder"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "1.0.20"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        val storePasswordEnv = System.getenv("SIGNING_STORE_PASSWORD")
        val keyAliasEnv = System.getenv("SIGNING_KEY_ALIAS")
        val keyPasswordEnv = System.getenv("SIGNING_KEY_PASSWORD")

        if (storePasswordEnv != null && keyAliasEnv != null && keyPasswordEnv != null) {
            create("release") {
                storeFile = file("release.keystore")
                storePassword = storePasswordEnv
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isCrunchPngs = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("SIGNING_STORE_PASSWORD") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.biometric)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.11.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    
    implementation(libs.webrtc)
    implementation(libs.tink.android)
    implementation(libs.coroutines.android)
    implementation(libs.navigation.compose)
    implementation(libs.zxing.android)
    
    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.database.ktx)
    
    // Explicit modern fragment version to prevent 16-bit requestCode crash from biometric lib pulling an old version
    implementation(libs.androidx.fragment.ktx)
}