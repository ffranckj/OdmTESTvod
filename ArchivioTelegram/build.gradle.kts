import java.util.Properties

plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

// 1. Carica il file local.properties in modo sicuro per la chiave API
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android { 
    namespace = "com.telegram.vod"
    compileSdk = 35

    defaultConfig {
        // 2. Prende la chiave da local.properties (o usa una stringa vuota se non la trova)
        val tmdbKey = localProperties.getProperty("TMDB_API") ?: "\"\""
        buildConfigField("String", "TMDB_API", tmdbKey)
    }

    // 3. Attiva la generazione della classe BuildConfig
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    val implementation by configurations
    val compileOnly by configurations
    val cloudstream by configurations

    // Core CloudStream API
    cloudstream("com.lagradost:cloudstream3:pre-release")
    
    // Librerie essenziali di sistema e scraping
    implementation("androidx.core:core-ktx:1.12.0") 
    implementation("com.github.Blatzar:NiceHttp:0.4.13")
    implementation("org.jsoup:jsoup:1.19.1")

    // Parsing JSON per TMDB e configurazioni interne
    compileOnly("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.16.0")
}

cloudstream {
    description = "Canale Telegram: Archivio Cinema Italiano (Copertine HD via TMDB)"
    authors = listOf("melan")
    language = "it" 
    status = 1 
    tvTypes = listOf("Movie")
}
