import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Flos for Windows: the same till screens and logic as the Android app (shared/), on Compose for Desktop.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.ksp)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets["main"].kotlin.srcDir("../shared/src/main/kotlin")
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.jb.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room.compiler)
    implementation(libs.jserialcomm)
}

compose.desktop {
    application {
        mainClass = "cz.flos.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Flos"
            packageVersion = "1.0.0"
            description = "Flos – pokladní systém"
            vendor = "Flos"
            // Extra JDK modules: XML and HTTPS for EET, printer code pages (CP852, Windows-1250),
            // settings storage (java.prefs) and printing through Windows printers (java.desktop).
            modules("java.xml", "java.naming", "java.sql", "java.prefs", "java.desktop", "java.logging", "jdk.crypto.ec", "jdk.charsets", "jdk.unsupported")
            windows {
                menu = true
                menuGroup = "Flos"
                shortcut = true
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "3f0a8c5e-6d1b-4c5e-9a57-0c1f5e2b7d41"
                iconFile.set(project.file("src/main/resources/flos.ico"))
            }
        }
    }
}
