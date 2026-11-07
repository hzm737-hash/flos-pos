plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "cz.flos.pos"
    // Google Play accepts new apps and updates only when they target Android 16 (API 36).
    compileSdk = 36

    defaultConfig {
        applicationId = "cz.flos.pos"
        minSdk = 26
        targetSdk = 36
        // Google Play needs a higher version code for every upload: CI passes its run number.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = build ?: 1
        versionName = if (build != null) "1.0.$build" else "0.1.0"
    }

    // Upload key for Google Play (Play App Signing re-signs the app with Google's key).
    // CI writes it from the repository secrets; without them release builds keep the debug key.
    val uploadKeystore = System.getenv("FLOS_UPLOAD_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (uploadKeystore != null) create("upload") {
            storeFile = uploadKeystore
            storePassword = System.getenv("FLOS_UPLOAD_PASSWORD")
            keyAlias = System.getenv("FLOS_UPLOAD_ALIAS") ?: "flos-upload"
            keyPassword = System.getenv("FLOS_UPLOAD_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the upload key when CI has it (Google Play bundle); otherwise with the debug key
            // so the APK can still be installed for testing.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
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
    // The till screens, database and logic are shared with the Windows app (desktop/).
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.sqlite.framework)
    ksp(libs.androidx.room.compiler)

    // Voluntary donations ("Podpořit Flos") through Google Play's own billing, as Play policy requires.
    implementation(libs.billing)
}
