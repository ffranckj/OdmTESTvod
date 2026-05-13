// File: C:/Users/melan/Downloads/apk/i/cloudstream-extensions-phisher/settings.gradle.kts (Originale di Phisher98)

rootProject.name = "CloudstreamPlugins"

// This file sets what projects are included. All new projects should get automatically included unless specified in "disabled" variable.
val disabled = listOf<String>() // Aggiungi qui "Odmtest" se hai una cartella con quel nome che non vuoi includere

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name) // Questo includerà automaticamente ":odmvodprovider"
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}

// pluginManagement { // Phisher98 non sembra avere questo nel suo settings.gradle.kts
//     repositories {
//         google()
//         mavenCentral()
//         gradlePluginPortal()
//     }
// }

// dependencyResolutionManagement { // Phisher98 non sembra avere questo
//     repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
//     repositories {
//         google()
//         mavenCentral()
//     }
// }
