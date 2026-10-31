package com.nuvio.tv.core.localaddon.program

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Call
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

/**
 * Records a continuous HTTP stream (for example an IPTV `.ts` channel) by copying bytes to a file.
 * Reconnects when the connection drops and starts a new file every segment.
 */
internal class RawStreamRecorder(
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

    fun abort() {
        activeCall?.cancel()
    }

    suspend fun run() {
        val startedAt = System.currentTimeMillis()
        var failures = 0
        var current: OutputStream? = null
        var fileStartedAt = 0L
        val buffer = ByteArray(64 * 1024)

        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (maxMillis > 0 && System.currentTimeMillis() - startedAt >= maxMillis) {
                    onLog("Reached the time limit")
                    break
                }
                val call = client.newCall(Request.Builder().url(input).header("User-Agent", userAgent).build())
                activeCall = call
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val source = response.body?.byteStream() ?: throw IOException("Empty response")
                        failures = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = source.read(buffer)
                            if (read < 0) break
                            val now = System.currentTimeMillis()
                            if (maxMillis > 0 && now - startedAt >= maxMillis) break
                            val rotate = segmentMillis > 0 && now - fileStartedAt >= segmentMillis
                            val stream = current?.takeIf { !rotate } ?: run {
                                current?.close()
                                val file = File(outputDir, "$baseName-${timestamp()}.ts")
                                onLog("Writing ${file.name}")
                                fileStartedAt = now
                                val opened = BufferedOutputStream(FileOutputStream(file))
                                current = opened
                                opened
                            }
                            stream.write(buffer, 0, read)
                        }
                    }
                    delay(RECONNECT_DELAY_MS) // the server closed the stream: reconnect
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    failures++
                    onLog("Connection error ($failures): ${error.message}")
                    if (failures >= MAX_FAILURES) throw error
                    delay(RECONNECT_DELAY_MS * failures.coerceAtMost(5))
                } finally {
                    activeCall = null
                }
            }
        } finally {
            runCatching { current?.close() }
        }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private companion object {
        const val MAX_FAILURES = 30
        const val RECONNECT_DELAY_MS = 2_000L
    }
}
