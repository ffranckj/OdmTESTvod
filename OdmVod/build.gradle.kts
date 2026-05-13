// File: OdmVod/build.gradle.kts

plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

android { 
    // Deve combaciare con il "package com.odmvod.vod" dei file Kotlin interni
    namespace = "com.odmvod.vod"
    compileSdk = 35
}

dependencies {
    val implementation by configurations
    val compileOnly by configurations
    val cloudstream by configurations

    // Core CloudStream API
    cloudstream("com.lagradost:cloudstream3:pre-release")
    
    // Librerie di sistema e parsing HTML per lo scraper nativo
    implementation("androidx.core:core-ktx:1.12.0") 
    implementation("org.jsoup:jsoup:1.19.1")

    // Parsing dati JSON integrati nelle pagine web
    compileOnly("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.16.0")
}

cloudstream {
    description = "Sito Web: OdmVod (Cinema Italiano Scraper Nativo)"
    authors = listOf("melan")
    language = "it" 
    status = 1 
    tvTypes = listOf("Movie")
}
