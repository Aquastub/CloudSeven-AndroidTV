package com.nuvio.tv.core.localaddon

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers requests for local addons (host [LOCAL_ADDON_HOST]) by running the addon on the device, so the
 * rest of the app can treat them like any other Stremio addon. Everything else passes through untouched.
 *
 *   /<slug>/manifest.json
 *   /<slug>/<resource>/<type>/<id>.json
 *   /<slug>/<resource>/<type>/<id>/<extra>.json      extra is "key=value&key=value"
 */
@Singleton
class LocalAddonInterceptor @Inject constructor(
    private val manager: LocalAddonManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != LOCAL_ADDON_HOST) return chain.proceed(request)

        val segments = request.url.pathSegments.filter { it.isNotEmpty() }
        val slug = segments.firstOrNull() ?: return reply(request, 404, """{"error":"not found"}""")
        val rest = segments.drop(1)

        val body: String? = when {
            rest.isEmpty() || rest == listOf("manifest.json") -> manager.manifestJson(slug)
            rest.size >= 3 -> {
                val extra = if (rest.size >= 4) parseExtra(rest[3].removeSuffix(".json")) else emptyMap()
                val id = if (rest.size >= 4) rest[2] else rest[2].removeSuffix(".json")
                runBlocking {
                    manager.handle(slug, LocalAddonRequest(resource = rest[0], type = rest[1], id = id, extra = extra))
                }
            }
            else -> null
        }
        return if (body == null) reply(request, 404, """{"error":"unknown local addon"}""") else reply(request, 200, body)
    }

    private fun parseExtra(raw: String): Map<String, String> =
        raw.split('&').mapNotNull { pair ->
            val index = pair.indexOf('=')
            if (index <= 0) return@mapNotNull null
            decode(pair.substring(0, index)) to decode(pair.substring(index + 1))
        }.toMap()

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun reply(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Not Found")
            .header("Cache-Control", "no-store")
            .header("Access-Control-Allow-Origin", "*")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
}
