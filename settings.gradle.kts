// File: settings.gradle.kts (Root del progetto)

rootProject.name = "CloudstreamPlugins"

// Aggiungi qui le cartelle che NON vuoi compilare (es. cartelle di test o backup)
val disabled = listOf("OdmTESTvod") 

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}

// Questo script scansiona la root e include automaticamente tutti i plugin validi
// (quindi includerà automaticamente ":ArchivioTelegram", ":OdmVod", ecc.)
File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}
