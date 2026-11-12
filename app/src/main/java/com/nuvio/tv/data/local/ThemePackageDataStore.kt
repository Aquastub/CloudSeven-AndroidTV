package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.theme.ThemePackage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemePackageDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profiles: ProfileManager
) {
    private val installedKey = stringPreferencesKey("installed_c7themes")
    private val activeKey = stringPreferencesKey("active_c7theme_id")
    private fun store(id: Int = profiles.activeProfileId.value) = factory.get(id, "theme_packages")

    val installed: Flow<List<ThemePackage>> = profiles.activeProfileId.flatMapLatest { id ->
        factory.get(id, "theme_packages").data.map { prefs ->
            runCatching {
                val array = JSONArray(prefs[installedKey] ?: "[]")
                (0 until array.length()).map { ThemePackage.fromJson(array.getJSONObject(it).toString()) }
            }.getOrDefault(emptyList())
        }
    }
    val active: Flow<ThemePackage?> = profiles.activeProfileId.flatMapLatest { id ->
        factory.get(id, "theme_packages").data.map { prefs ->
            val activeId = prefs[activeKey] ?: return@map null
            runCatching {
                val array = JSONArray(prefs[installedKey] ?: "[]")
                (0 until array.length()).map { ThemePackage.fromJson(array.getJSONObject(it).toString()) }.firstOrNull { it.id == activeId }
            }.getOrNull()
        }
    }

    suspend fun install(theme: ThemePackage) = store().edit { prefs ->
        val old = JSONArray(prefs[installedKey] ?: "[]")
        val next = JSONArray()
        for (i in 0 until old.length()) if (old.getJSONObject(i).optString("id") != theme.id) next.put(old.getJSONObject(i))
        val obj = JSONObject().put("schemaVersion", 1).put("id", theme.id).put("name", theme.name)
            .put("author", theme.author).put("description", theme.description).put("version", theme.version)
        fun addMap(key: String, map: Map<String, String>) { val value = JSONObject(); map.forEach { (key, entry) -> value.put(key, entry) }; obj.put(key, value) }
        addMap("colors", theme.colors); addMap("layout", theme.layout); addMap("components", theme.components)
        next.put(obj); prefs[installedKey] = next.toString()
    }
    suspend fun activate(id: String?) = store().edit { prefs ->
        if (id == null) prefs.remove(activeKey) else {
            val arr = JSONArray(prefs[installedKey] ?: "[]")
            require((0 until arr.length()).any { arr.getJSONObject(it).optString("id") == id })
            prefs[activeKey] = id
        }
    }
    suspend fun remove(id: String) = store().edit { prefs ->
        val old = JSONArray(prefs[installedKey] ?: "[]"); val next = JSONArray()
        for (i in 0 until old.length()) if (old.getJSONObject(i).optString("id") != id) next.put(old.getJSONObject(i))
        prefs[installedKey] = next.toString(); if (prefs[activeKey] == id) prefs.remove(activeKey)
    }
}
