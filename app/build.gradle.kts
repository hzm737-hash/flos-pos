plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "cz.flos.pos"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.flos.pos"
        minSdk = 26
        targetSdk = 35
        // Every CI build gets a higher version code (its run number), so a phone installs it as an update.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = build ?: 1
        versionName = if (build != null) "1.0.$build" else "0.1.0"
    }

    // One permanent key for every build. Android installs a new APK over the old one (keeping all data)
    // only when both are signed with the same key. CI writes the key from the repository secrets
    // FLOS_UPLOAD_KEYSTORE_BASE64 + FLOS_UPLOAD_PASSWORD; without them builds keep the random debug key.
    val flosKeystore = System.getenv("FLOS_UPLOAD_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (flosKeystore != null) create("flos") {
            storeFile = flosKeystore
            storePassword = System.getenv("FLOS_UPLOAD_PASSWORD")
            keyAlias = System.getenv("FLOS_UPLOAD_ALIAS") ?: "flos-upload"
            keyPassword = System.getenv("FLOS_UPLOAD_PASSWORD")
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("flos")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the permanent key when CI has it; otherwise with the debug key for testing.
            signingConfig = signingConfigs.findByName("flos") ?: signingConfigs.getByName("debug")
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
}
