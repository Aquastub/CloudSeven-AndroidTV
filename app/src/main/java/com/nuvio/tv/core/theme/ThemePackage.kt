package com.nuvio.tv.core.theme

import android.graphics.Color as AndroidColor
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Safe, declarative subset of the Cloud 7 .c7theme format. */
data class ThemePackage(
    val id: String,
    val name: String,
    val author: String,
    val description: String,
    val version: String,
    val colors: Map<String, String>,
    val layout: Map<String, String>,
    val components: Map<String, String>
) {
    fun color(key: String, fallback: Int): Int = colors[key]?.let {
        runCatching { AndroidColor.parseColor(it) }.getOrNull()
    } ?: fallback

    fun posterCornerRadiusDp(fallback: Float): Float = components["poster.cornerRadiusDp"]
        ?.toFloatOrNull()?.takeIf { it in 0f..40f } ?: fallback

    companion object {
        fun fromJson(source: String): ThemePackage {
            val root = JSONObject(source)
            require(root.optInt("schemaVersion", 1) == 1) { "Unsupported theme schema" }
            val id = root.getString("id")
            require(Regex("[a-z0-9][a-z0-9._-]{1,63}").matches(id)) { "Invalid theme ID" }
            val name = root.getString("name").trim()
            require(name.isNotEmpty() && name.length <= 80) { "Invalid theme name" }
            fun stringMap(key: String, max: Int): Map<String, String> {
                val obj = root.optJSONObject(key) ?: return emptyMap()
                require(obj.length() <= max) { "Too many $key entries" }
                return buildMap {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next(); val v = obj.optString(k)
                        require(k.length <= 96 && v.length <= 120) { "Invalid $key token" }
                        if (key == "colors") require(Regex("#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})").matches(v)) { "Invalid color: $k" }
                        put(k, v)
                    }
                }
            }
            val assets = root.optJSONObject("assets")
            if (assets != null) {
                val keys = assets.keys()
                while (keys.hasNext()) {
                    val path = assets.optString(keys.next())
                    require(!path.startsWith('/') && '\\' !in path && path.split('/').none { it == ".." || it == "." }) { "Unsafe asset path" }
                }
            }
            return ThemePackage(id, name, root.optString("author", "Unknown"), root.optString("description"),
                root.optString("version", "1.0.0"), stringMap("colors", 64), stringMap("layout", 64), stringMap("components", 128))
        }

        fun readArchive(bytes: ByteArray): ThemePackage {
            require(bytes.size <= 8 * 1024 * 1024) { "Theme archive exceeds 8 MB" }
            var manifest: String? = null
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.name.startsWith('/') && '\\' !in entry.name && entry.name.split('/').none { it == ".." || it == "." }) { "Unsafe archive path" }
                    if (entry.name == "manifest.json") {
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096); var total = 0
                        while (true) { val n = zip.read(buffer); if (n < 0) break; total += n; require(total <= 256 * 1024) { "Manifest too large" }; out.write(buffer, 0, n) }
                        manifest = out.toString(Charsets.UTF_8.name())
                        break
                    }
                    zip.closeEntry()
                }
            }
            return fromJson(requireNotNull(manifest) { "manifest.json not found at archive root" })
        }
    }
}
