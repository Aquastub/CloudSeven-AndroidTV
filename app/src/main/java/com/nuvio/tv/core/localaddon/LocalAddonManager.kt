package com.nuvio.tv.core.localaddon

import android.util.Log
import com.nuvio.tv.core.plugin.PluginRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs, lists and runs local addons (ADINST packages).
 *
 * An ADINST is a ZIP (renamed to .adinst) with a Stremio-style `manifest.json` and a JavaScript file
 * (`index.js`, or the file named by `"main"`) that exports `handle(request)`. Files may sit inside one
 * wrapping folder. Extra `nuvio` fields in the manifest: `permissions` ("python", "preroll"...) and
 * `settings` (values the user can fill in).
 */
@Singleton
class LocalAddonManager @Inject constructor(
    private val store: ZipAddonStore,
    private val runtime: PluginRuntime,
    private val programs: LocalAddonPrograms,
) {
    sealed interface InstallResult {
        data class Success(val slug: String, val name: String, val manifestUrl: String, val updated: Boolean) : InstallResult
        data class Error(val message: String) : InstallResult
    }

    /** What the confirmation step shows before anything is installed. */
    data class Inspection(val name: String, val permissions: Set<String>)

    private val addons = ConcurrentHashMap<String, InstalledLocalAddon>()

    @Volatile
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching { store.loadAddons() }
                .onFailure { Log.w(TAG, "Could not read installed addons: ${it.message}") }
                .getOrDefault(emptyMap())
                .forEach { (slug, files) ->
                    runCatching { addons[slug] = buildAddon(slug, files) }
                        .onFailure { Log.w(TAG, "Skipping addon $slug: ${it.message}") }
                }
            loaded = true
        }
    }

    fun installed(): List<InstalledLocalAddon> {
        ensureLoaded()
        return addons.values.sortedBy { it.name.lowercase() }
    }

    fun get(slug: String): InstalledLocalAddon? {
        ensureLoaded()
        return addons[slug]
    }

    fun manifestJson(slug: String): String? = get(slug)?.manifestJson

    fun remove(slug: String) {
        ensureLoaded()
        addons.remove(slug)
        runCatching { store.deleteAddon(slug) }
    }

    fun inspect(packagePath: String): Inspection? = runCatching {
        val names = store.entryNames(packagePath)
        val files = rekey(store.readSmallFiles(packagePath), rootPrefix(names))
        val manifest = parseManifest(files["manifest.json"]?.decodeToString().orEmpty())
        Inspection(name = manifest.optString("name"), permissions = readPermissions(manifest))
    }.getOrNull()

    suspend fun install(packagePath: String): InstallResult = withContext(Dispatchers.IO) {
        ensureLoaded()
        try {
            val names: Set<String>
            val prefix: String
            val files: Map<String, ByteArray>
            try {
                names = store.entryNames(packagePath)
                prefix = rootPrefix(names)
                files = rekey(store.readSmallFiles(packagePath), prefix)
            } catch (error: Throwable) {
                return@withContext InstallResult.Error(error.message ?: "Not a valid ADINST file")
            }
            val slug: String
            val addon: InstalledLocalAddon
            try {
                slug = slugFor(files)
                addon = buildAddon(slug, files)
            } catch (error: IllegalArgumentException) {
                return@withContext InstallResult.Error(error.message ?: "Invalid addon")
            }
            val updated = addons.containsKey(slug)
            try {
                store.installPackage(slug, packagePath, prefix)
            } catch (error: Throwable) {
                return@withContext InstallResult.Error(error.message ?: "Could not save the addon")
            }
            addons[slug] = addon
            InstallResult.Success(slug = slug, name = addon.name, manifestUrl = localAddonManifestUrl(slug), updated = updated)
        } finally {
            store.discard(packagePath)
        }
    }

    /** Runs the addon's handle() for one request and returns the JSON it produced ("{}" when it failed). */
    suspend fun handle(slug: String, request: LocalAddonRequest): String {
        val addon = get(slug) ?: return "{}"
        val settingValues = store.loadSettings(slug).filterKeys { key -> addon.settings.any { it.key == key } }
        val requestJson = JSONObject().apply {
            put("resource", request.resource)
            put("type", request.type)
            put("id", request.id)
            put("extra", JSONObject(request.extra))
            put("settings", JSONObject(settingValues))
        }.toString()
        return runtime.executeLocalAddon(
            slug = slug,
            code = addon.code,
            requestJson = requestJson,
            storageCall = { operation, key, value -> storage(slug, operation, key, value) },
            programCall = { operation, payload -> programs.call(slug, addon.permissions, operation, payload) },
        )
    }

    private fun storage(slug: String, operation: String, key: String, value: String): String =
        runCatching {
            when (operation) {
                "get" -> JSONObject().put("ok", true).put("value", store.storageGet(slug, key) ?: JSONObject.NULL)
                "set" -> {
                    store.storageSet(slug, key, value)
                    JSONObject().put("ok", true)
                }
                else -> JSONObject().put("ok", false).put("error", "Unknown operation")
            }.toString()
        }.getOrElse { error ->
            JSONObject().put("ok", false).put("error", error.message ?: "Storage failed").toString()
        }

    private fun buildAddon(slug: String, files: Map<String, ByteArray>): InstalledLocalAddon {
        val manifestText = files["manifest.json"]?.decodeToString()
            ?: throw IllegalArgumentException("manifest.json is missing")
        val manifest = parseManifest(manifestText)
        val mainPath = manifest.optString("main").trim().removePrefix("./").ifBlank { DEFAULT_MAIN }
        val script = files[mainPath]
            ?: throw IllegalArgumentException("$mainPath is missing (set \"main\" in manifest.json)")
        require(script.size <= MAX_SCRIPT_BYTES) { "$mainPath is larger than 2 MB" }
        return InstalledLocalAddon(
            slug = slug,
            name = manifest.optString("name"),
            manifestJson = manifestText,
            code = script.decodeToString(),
            permissions = readPermissions(manifest),
            settings = readSettings(manifest),
        )
    }

    /** `"nuvio": { "permissions": ["python", "preroll"] }` in manifest.json. */
    private fun readPermissions(manifest: JSONObject): Set<String> {
        val array = manifest.optJSONObject("nuvio")?.optJSONArray("permissions") ?: return emptySet()
        return (0 until array.length()).mapNotNull { array.optString(it).trim().ifBlank { null } }.toSet()
    }

    /** `"nuvio": { "settings": [{ "key": "playlistUrl", "label": "Playlist URL", "type": "url" }] }` */
    private fun readSettings(manifest: JSONObject): List<LocalAddonSetting> {
        val array: JSONArray = manifest.optJSONObject("nuvio")?.optJSONArray("settings") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val key = obj.optString("key").trim().takeIf { SETTING_KEY_REGEX.matches(it) } ?: return@mapNotNull null
            val label = obj.optString("label").trim().ifBlank { key }
            val type = obj.optString("type").lowercase().takeIf { it in SETTING_TYPES } ?: "text"
            LocalAddonSetting(key = key, label = label.take(60), type = type, hint = obj.optString("hint").takeIf { it.isNotBlank() }?.take(160))
        }.take(MAX_SETTINGS)
    }

    private fun parseManifest(text: String): JSONObject {
        val manifest = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw IllegalArgumentException("manifest.json is not valid JSON")
        }
        require(manifest.optString("id").isNotBlank()) { "manifest.json needs an \"id\"" }
        require(manifest.optString("name").isNotBlank()) { "manifest.json needs a \"name\"" }
        require((manifest.optJSONArray("resources")?.length() ?: 0) > 0) { "manifest.json needs a non-empty \"resources\" list" }
        return manifest
    }

    private fun slugFor(files: Map<String, ByteArray>): String {
        val manifest = parseManifest(files["manifest.json"]?.decodeToString() ?: throw IllegalArgumentException("manifest.json is missing"))
        val slug = manifest.optString("id").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48)
        require(slug.isNotEmpty()) { "manifest.json \"id\" must contain letters or digits" }
        return slug
    }

    /** The wrapping folder to strip (like "my-addon/") so manifest.json sits at the root; "" if it already does. */
    private fun rootPrefix(names: Collection<String>): String {
        if ("manifest.json" in names) return ""
        val tops = names.map { it.substringBefore('/', missingDelimiterValue = "") }.toSet()
        val single = tops.singleOrNull()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("manifest.json was not found in the ADINST")
        val prefix = "$single/"
        if ("${prefix}manifest.json" !in names) throw IllegalArgumentException("manifest.json was not found in the ADINST")
        return prefix
    }

    private fun rekey(raw: Map<String, ByteArray>, prefix: String): Map<String, ByteArray> =
        raw.filterKeys { it.isNotEmpty() && it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }

    private companion object {
        const val TAG = "LocalAddonManager"
        const val DEFAULT_MAIN = "index.js"
        const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024
        const val MAX_SETTINGS = 12
        val SETTING_KEY_REGEX = Regex("[A-Za-z0-9_]{1,32}")
        val SETTING_TYPES = setOf("text", "url", "password", "number")
    }
}
