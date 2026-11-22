plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.perezfranco.balrog"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.perezfranco.balrog"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.5.2"
    }

    signingConfigs {
        // Android only installs an update over an existing app if both are
        // signed with the same key. Builds pick up a fixed key from the
        // environment when one is provided (see the release workflow);
        // otherwise they use the machine's throwaway debug key.
        getByName("debug") {
            System.getenv("BALROG_KEYSTORE")?.takeIf { it.isNotEmpty() }?.let { path ->
                storeFile = file(path)
                storePassword = System.getenv("BALROG_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BALROG_KEY_ALIAS")
                keyPassword = System.getenv("BALROG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.biometric)
    implementation(libs.google.material)
    implementation(libs.zxing.android.embedded)
    testImplementation(libs.junit)
    // The android.jar org.json classes are stubs; this makes them real in unit tests.
    testImplementation(libs.org.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
