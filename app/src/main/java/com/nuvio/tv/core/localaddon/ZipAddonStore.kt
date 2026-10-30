package com.nuvio.tv.core.localaddon

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage for installed ADINST addons. Packages can carry Python libraries and big files, so they are
 * handled as files on disk and unpacked as a stream, never held in memory as a whole.
 *
 *   filesDir/local_addons/<slug>/        the unpacked package
 *   filesDir/local_addon_data/<slug>/    private working folder (settings, storage, Python scratch)
 */
@Singleton
class ZipAddonStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val root = File(context.filesDir, "local_addons").also { it.mkdirs() }
    private val dataRoot = File(context.filesDir, "local_addon_data").also { it.mkdirs() }

    /** Names of every file in the package (safe, "/"-separated). */
    fun entryNames(path: String): Set<String> = ZipFile(File(path)).use { zip ->
        val names = LinkedHashSet<String>()
        for (entry in zip.entries()) {
            if (entry.isDirectory) continue
            require(names.size < MAX_ENTRIES) { "The ADINST has too many files" }
            names += safeName(entry.name)
        }
        require(names.isNotEmpty()) { "The file is empty or not a valid ADINST (ZIP) package" }
        names
    }

    /** The package's manifest and scripts, read into memory. Everything else is only unpacked. */
    fun readSmallFiles(path: String): Map<String, ByteArray> = ZipFile(File(path)).use { zip ->
        val files = LinkedHashMap<String, ByteArray>()
        for (entry in zip.entries()) {
            if (entry.isDirectory || entry.size !in 0..SMALL_FILE_BYTES) continue
            val name = safeName(entry.name)
            if (!isScriptOrManifest(name)) continue
            files[name] = readBounded(zip, entry, SMALL_FILE_BYTES)
        }
        files
    }

    /**
     * Unpacks the whole package into the addon's folder, stripping [prefix] (a wrapping folder).
     * The old version is replaced only once everything has been written.
     */
    fun installPackage(slug: String, path: String, prefix: String) {
        val target = addonDir(slug)
        val staging = File(root, ".$slug.new").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        try {
            var total = 0L
            ZipFile(File(path)).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val name = safeName(entry.name)
                    if (!name.startsWith(prefix)) continue
                    val out = File(staging, name.removePrefix(prefix))
                    check(out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Unsafe path in ADINST" }
                    out.parentFile?.mkdirs()
                    var written = 0L
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(out).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                written += read
                                total += read
                                require(written <= MAX_FILE_BYTES) { "A file in the ADINST is too large" }
                                require(total <= MAX_PACKAGE_BYTES) { "The ADINST is too large when unpacked" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
            target.deleteRecursively()
            check(staging.renameTo(target)) { "Could not save the addon" }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    /** Manifest and scripts of every installed addon (slug -> relative path -> bytes). */
    fun loadAddons(): Map<String, Map<String, ByteArray>> =
        root.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }.orEmpty().associate { dir ->
            dir.name to dir.walkTopDown()
                .filter { it.isFile && it.length() <= SMALL_FILE_BYTES && isScriptOrManifest(it.name) }
                .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes() }
        }

    fun deleteAddon(slug: String) {
        addonDir(slug).deleteRecursively()
        dataDir(slug).deleteRecursively()
    }

    fun addonDir(slug: String): File {
        require(SLUG_REGEX.matches(slug)) { "Invalid addon id" }
        return File(root, slug)
    }

    /** The addon's private working folder, kept when the addon is updated. */
    fun dataDir(slug: String): File {
        require(SLUG_REGEX.matches(slug)) { "Invalid addon id" }
        return File(dataRoot, slug).also { it.mkdirs() }
    }

    /** The user's values for the addon's settings (key -> text). */
    fun loadSettings(slug: String): Map<String, String> = runCatching {
        val file = File(dataDir(slug), "settings.json")
        if (!file.isFile) return@runCatching emptyMap<String, String>()
        val json = JSONObject(file.readText())
        json.keys().asSequence().associateWith { json.optString(it, "") }
    }.getOrDefault(emptyMap())

    fun saveSettings(slug: String, values: Map<String, String>) {
        val json = JSONObject()
        values.forEach { (key, value) -> json.put(key, value) }
        File(dataDir(slug), "settings.json").writeText(json.toString())
    }

    /** nuvio.storage: a small private key/value store per addon (cached lists, state...). */
    fun storageGet(slug: String, key: String): String? {
        val file = storageFile(slug, key)
        return if (file.isFile) file.readText() else null
    }

    fun storageSet(slug: String, key: String, value: String) {
        require(value.length <= MAX_STORAGE_CHARS) { "Value is too large" }
        storageFile(slug, key).writeText(value)
    }

    fun discard(path: String) {
        runCatching { File(path).delete() }
    }

    private fun storageFile(slug: String, key: String): File {
        require(STORAGE_KEY_REGEX.matches(key)) { "Invalid storage key" }
        return File(File(dataDir(slug), "storage").also { it.mkdirs() }, key)
    }

    private fun readBounded(zip: ZipFile, entry: ZipEntry, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        zip.getInputStream(entry).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= limit) { "A file in the ADINST is larger than it says" }
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    private fun isScriptOrManifest(name: String): Boolean = name.endsWith(".json") || name.endsWith(".js")

    /** Rejects absolute paths and "..", so a package cannot write outside its addon folder. */
    private fun safeName(raw: String): String {
        val name = raw.replace('\\', '/').trimStart('/')
        require(name.split('/').none { it == ".." }) { "The ADINST contains an unsafe path" }
        return name
    }

    companion object {
        private const val MAX_ENTRIES = 5000
        const val MAX_PACKAGE_BYTES = 300L * 1024 * 1024
        private const val MAX_FILE_BYTES = 250L * 1024 * 1024
        private const val SMALL_FILE_BYTES = 2L * 1024 * 1024
        private const val MAX_STORAGE_CHARS = 16 * 1024 * 1024
        private val SLUG_REGEX = Regex("[a-z0-9-]+")
        private val STORAGE_KEY_REGEX = Regex("[A-Za-z0-9._-]{1,64}")
    }
}
