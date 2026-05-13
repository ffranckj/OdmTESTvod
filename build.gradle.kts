import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.dsl.LibraryExtension

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// Funzioni helper per la risoluzione sicura dei blocchi di estensione
fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()
fun Project.android(configuration: LibraryExtension.() -> Unit) = extensions.configure<LibraryExtension>("android", configuration)

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/melan/cloudstream-extensions")
        authors = listOf("melan")
    }

    android {
        namespace = "com.myplugin.vod"
        compileSdk = 35

        defaultConfig {
            minSdk = 21
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        buildFeatures {
            buildConfig = true
        }

        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val implementation by configurations
        val compileOnly by configurations
        val cloudstream by configurations

        // Core CloudStream API
        cloudstream("com.lagradost:cloudstream3:pre-release")
        implementation(kotlin("stdlib"))

        // Librerie Android & Scraping
        implementation("androidx.core:core-ktx:1.12.0")
        implementation("com.github.Blatzar:NiceHttp:0.4.13")
        implementation("org.jsoup:jsoup:1.19.1")

        // Parsing e Serializzazione
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
        implementation("com.google.code.gson:gson:2.11.0")

        // Coroutines & Utility avanzate
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
        implementation("org.mozilla:rhino:1.8.0")
        implementation("me.xdrop:fuzzywuzzy:1.4.0")
        implementation("app.cash.quickjs:quickjs-android:0.9.2")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
