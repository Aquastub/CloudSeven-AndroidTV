package com.nuvio.tv.core.localaddon.program

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Records a live HLS stream by downloading its segments and appending them to a file
 * (MPEG-TS, or fragmented MP4 when the playlist has an init segment). No ffmpeg needed.
 * Handles master playlists (picks the best quality), AES-128 encryption and playlist reloads.
 */
internal class HlsRecorder(
    private val client: OkHttpClient,
    private val input: String,
    private val userAgent: String,
    private val outputDir: File,
    private val baseName: String,
    private val segmentMillis: Long,
    private val maxMillis: Long,
    private val onLog: (String) -> Unit,
) {
    @Volatile
    private var activeCall: Call? = null

    /** Interrupts a download in progress so a stop request takes effect immediately. */
    fun abort() {
        activeCall?.cancel()
    }

    private class KeyInfo(val uri: String, val iv: ByteArray?)
    private class Segment(val url: String, val key: KeyInfo?)
    private class Playlist(
        val firstSequence: Long,
        val targetDurationMillis: Long,
        val segments: List<Segment>,
        val ended: Boolean,
        val initUrl: String?,
    )

    suspend fun run() {
        val startedAt = System.currentTimeMillis()
        val mediaUrl = resolveMediaPlaylist(input)
        onLog("Recording $mediaUrl")

        var lastSequence = Long.MIN_VALUE
        var current: OutputStream? = null
        var fileStartedAt = 0L
        var failures = 0
        val keyCache = HashMap<String, ByteArray>()

        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (maxMillis > 0 && System.currentTimeMillis() - startedAt >= maxMillis) {
                    onLog("Reached the time limit")
                    break
                }

                val playlist = try {
                    parse(fetchText(mediaUrl), mediaUrl)
                } catch (error: IOException) {
                    failures++
                    onLog("Playlist error ($failures): ${error.message}")
                    if (failures >= MAX_FAILURES) throw error
                    delay(RETRY_DELAY_MS * failures.coerceAtMost(5))
                    continue
                }
                failures = 0

                if (lastSequence == Long.MIN_VALUE) {
                    // Start close to the live edge instead of replaying the whole window.
                    val skip = (playlist.segments.size - START_BACKLOG_SEGMENTS).coerceAtLeast(0)
                    lastSequence = playlist.firstSequence + skip - 1
                }

                for ((index, segment) in playlist.segments.withIndex()) {
                    val sequence = playlist.firstSequence + index
                    if (sequence <= lastSequence) continue
                    currentCoroutineContext().ensureActive()

                    val data = downloadSegment(segment, sequence, keyCache)
                    lastSequence = sequence
                    if (data == null) continue // skipped after retries

                    val now = System.currentTimeMillis()
                    val rotate = segmentMillis > 0 && now - fileStartedAt >= segmentMillis
                    val stream = current?.takeIf { !rotate } ?: run {
                        current?.close()
                        val extension = if (playlist.initUrl != null) "mp4" else "ts"
                        val file = File(outputDir, "$baseName-${timestamp()}.$extension")
                        onLog("Writing ${file.name}")
                        fileStartedAt = now
                        val opened = BufferedOutputStream(FileOutputStream(file))
                        playlist.initUrl?.let { opened.write(fetchBytes(it)) }
                        current = opened
                        opened
                    }
                    stream.write(data)
                    stream.flush()
                }

                if (playlist.ended) {
                    onLog("The stream ended")
                    break
                }
                delay((playlist.targetDurationMillis / 2).coerceIn(1_000L, 5_000L))
            }
        } finally {
            runCatching { current?.close() }
        }
    }

    private suspend fun downloadSegment(segment: Segment, sequence: Long, keyCache: HashMap<String, ByteArray>): ByteArray? {
        var lastError: IOException? = null
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                val bytes = fetchBytes(segment.url)
                val key = segment.key ?: return bytes
                val keyBytes = keyCache.getOrPut(key.uri) { fetchBytes(key.uri) }
                if (keyBytes.size != 16) throw IOException("Unexpected key size ${keyBytes.size}")
                val iv = key.iv ?: sequenceIv(sequence)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
                return cipher.doFinal(bytes)
            } catch (error: IOException) {
                lastError = error
                delay(RETRY_DELAY_MS * (attempt + 1))
            } catch (error: java.security.GeneralSecurityException) {
                lastError = IOException("Could not decrypt a segment: ${error.message}")
                return null
            }
        }
        onLog("Skipped a segment: ${lastError?.message}")
        return null
    }

    private fun sequenceIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        for (i in 0 until 8) iv[15 - i] = (sequence shr (8 * i)).toByte()
        return iv
    }

    private suspend fun resolveMediaPlaylist(url: String): String {
        val text = fetchText(url)
        if (!text.contains("#EXT-X-STREAM-INF")) return url
        var bestBandwidth = -1L
        var best: String? = null
        var pendingBandwidth = -1L
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pendingBandwidth = attr(line, "BANDWIDTH")?.toLongOrNull() ?: 0L
                line.isEmpty() || line.startsWith("#") -> Unit
                pendingBandwidth >= 0 -> {
                    if (pendingBandwidth > bestBandwidth) {
                        bestBandwidth = pendingBandwidth
                        best = resolve(url, line)
                    }
                    pendingBandwidth = -1L
                }
            }
        }
        return best ?: url
    }

    private fun parse(text: String, baseUrl: String): Playlist {
        var sequence = 0L
        var targetMillis = 6_000L
        var ended = false
        var initUrl: String? = null
        var key: KeyInfo? = null
        val segments = ArrayList<Segment>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-TARGETDURATION:") ->
                    targetMillis = ((line.substringAfter(':').trim().toDoubleOrNull() ?: 6.0) * 1000).toLong()
                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.startsWith("#EXT-X-MAP:") -> initUrl = attr(line, "URI")?.let { resolve(baseUrl, it) }
                line.startsWith("#EXT-X-KEY:") -> {
                    key = when (val method = attr(line, "METHOD")) {
                        null, "NONE" -> null
                        "AES-128" -> KeyInfo(
                            uri = resolve(baseUrl, attr(line, "URI") ?: throw IOException("Key URI missing")),
                            iv = attr(line, "IV")?.let(::hexToBytes),
                        )
                        else -> throw IOException("Unsupported encryption: $method")
                    }
                }
                line.startsWith("#") -> Unit
                else -> segments += Segment(resolve(baseUrl, line), key)
            }
        }
        return Playlist(sequence, targetMillis, segments, ended, initUrl)
    }

    private fun attr(line: String, name: String): String? {
        val match = Regex("[,:]$name=(\"([^\"]*)\"|[^,]*)").find(line) ?: return null
        return match.groupValues[2].ifEmpty { match.groupValues[1].trim('"') }
    }

    private fun hexToBytes(value: String): ByteArray {
        val hex = value.removePrefix("0x").removePrefix("0X").padStart(32, '0').takeLast(32)
        return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun resolve(base: String, reference: String): String =
        base.toHttpUrlOrNull()?.resolve(reference)?.toString() ?: throw IOException("Bad URL: $reference")

    private fun fetchText(url: String): String = String(fetchBytes(url), Charsets.UTF_8)

    private fun fetchBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        val call = client.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val bytes = response.body?.bytes() ?: throw IOException("Empty response")
                if (bytes.size > MAX_DOWNLOAD_BYTES) throw IOException("Response too large")
                return bytes
            }
        } finally {
            activeCall = null
        }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private companion object {
        const val MAX_FAILURES = 30
        const val RETRY_DELAY_MS = 1_000L
        const val START_BACKLOG_SEGMENTS = 2
        const val MAX_DOWNLOAD_BYTES = 64 * 1024 * 1024
    }
}
