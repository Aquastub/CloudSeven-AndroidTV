package com.nuvio.tv.core.localaddon

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Local addons (ADINST packages) run on the device. They look like ordinary Stremio addons to the rest of
 * the app: each one is reachable at https://[LOCAL_ADDON_HOST]/<slug>/..., a host that never resolves on
 * the network, and [LocalAddonInterceptor] answers those requests by running the addon's script.
 */
const val LOCAL_ADDON_HOST = "local.addon.invalid"

const val PERMISSION_PYTHON = "python"
const val PERMISSION_PROGRAMS = "programs"
const val PERMISSION_PREROLL = "preroll"
const val PERMISSION_PLAYER_UI = "playerUi"

fun localAddonBaseUrl(slug: String): String = "https://$LOCAL_ADDON_HOST/$slug"

fun localAddonManifestUrl(slug: String): String = "${localAddonBaseUrl(slug)}/manifest.json"

fun String.isLocalAddonUrl(): Boolean = toHttpUrlOrNull()?.host == LOCAL_ADDON_HOST

/** One Stremio-style request, for example stream / series / tt0944947:1:2 (extra: skip=20). */
data class LocalAddonRequest(
    val resource: String,
    val type: String,
    val id: String,
    val extra: Map<String, String> = emptyMap(),
)

/** A setting the addon asks the user for (`nuvio.settings` in manifest.json). */
data class LocalAddonSetting(
    val key: String,
    val label: String,
    val type: String,
    val hint: String?,
)

data class InstalledLocalAddon(
    val slug: String,
    val name: String,
    val manifestJson: String,
    val code: String,
    val permissions: Set<String>,
    val settings: List<LocalAddonSetting>,
)

/** What the player knows about the episode that is about to start (sent to addons with the preroll permission). */
data class PrerollRequest(
    val contentType: String?,
    val title: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val videoId: String?,
    val parentMetaId: String,
)

/** A short clip an addon wants played before an episode. */
data class PrerollClip(val url: String, val headers: Map<String, String> = emptyMap())
