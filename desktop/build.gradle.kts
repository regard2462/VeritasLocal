import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Muss zu versionName in app/build.gradle.kts passen
val vlVersion = "1.3.1"

// Gemeinsamer Code liegt im Android-Modul. Was nur auf Android laeuft, bleibt draussen; die Gegenstuecke liegen in
// src/main/kotlin (schmale Ersatzklassen fuer android.*, Fenster, Dateidialog).
val sharedSrc = file("../app/src/main/java")
val androidOnly = listOf(
    "com/veritas/localresearch/MainActivity.kt",
    "com/veritas/localresearch/scraping/WebViewPageRenderer.kt",
    "com/veritas/localresearch/export/PdfWriter.kt",
    "com/veritas/localresearch/scraping/PdfTextExtractor.kt",
)

// PDF lesen und schreiben: dieselben Dateien wie auf Android, mit Apache PDFBox statt der Android-Portierung
val pdfSources = tasks.register<Sync>("pdfSources") {
    from(sharedSrc) {
        include("com/veritas/localresearch/export/PdfWriter.kt", "com/veritas/localresearch/scraping/PdfTextExtractor.kt")
        filter { line ->
            if (line.contains("PDFBoxResourceLoader")) "" else line.replace("com.tom_roush.pdfbox", "org.apache.pdfbox")
        }
        rename { it.replace(".kt", "Desktop.kt") }
    }
    into(layout.buildDirectory.dir("generated/pdf"))
}

kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.apply {
        srcDir(sharedSrc).exclude(androidOnly)
        srcDir("src/main/kotlin")
        srcDir(pdfSources)
    }
    sourceSets["test"].kotlin.srcDir("../app/src/test/java")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.microsoft.onnxruntime:onnxruntime:1.31.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.19.1")
    implementation("org.apache.pdfbox:pdfbox:2.0.37")

    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.veritas.localresearch.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Xmx2g")
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi)
            packageName = "VeritasLocal"
            packageVersion = vlVersion
            description = "Lokale Deep-Research-Recherche mit geprüften Zitaten"
            vendor = "VeritasLocal"
            copyright = "AGPL-3.0-or-later"
            licenseFile.set(rootProject.file("LICENSE"))
            // llama.cpp-Bibliotheken je Betriebssystem (native-libs/windows-x64, native-libs/linux-x64)
            appResourcesRootDir.set(project.layout.projectDirectory.dir("native-libs"))
            modules("java.naming", "java.net.http", "jdk.crypto.ec", "jdk.unsupported", "java.management", "jdk.management", "java.sql", "jdk.localedata")
            windows {
                menuGroup = "VeritasLocal"
                upgradeUuid = "3f6b1e52-8c47-4f0a-9d2e-5a7c1b9e4d63"
                perUserInstall = true
                dirChooser = true
                shortcut = true
                menu = true
                iconFile.set(project.file("icons/veritas.ico"))
            }
            linux {
                packageName = "veritaslocal"
                appCategory = "Education"
                menuGroup = "Education"
                shortcut = true
                iconFile.set(project.file("icons/veritas.png"))
            }
        }
    }
}
