package com.nuvio.tv.core.localaddon

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The most recent log lines of local addons (their console output, errors, pre-roll lookups, recordings).
 * Kept in memory only. Shown on the addon management web page, which is far easier to read from a phone.
 */
@Singleton
class LocalAddonDiagnostics @Inject constructor() {
    private data class Entry(val time: Long, val source: String, val level: String, val message: String)

    private val entries = ConcurrentLinkedDeque<Entry>()

    fun record(source: String, message: String, level: String = "info") {
        entries.addLast(Entry(System.currentTimeMillis(), source, level, message.take(MAX_MESSAGE_CHARS)))
        while (entries.size > MAX_ENTRIES) entries.pollFirst()
    }

    /** Newest last. [source] is an addon id, "preroll" or "programs"; null returns everything. */
    fun lines(source: String? = null): List<String> {
        val format = SimpleDateFormat("HH:mm:ss", Locale.US)
        return entries.filter { source == null || it.source == source }
            .takeLast(MAX_RETURNED)
            .map { "${format.format(Date(it.time))} [${it.source}] ${it.level.uppercase(Locale.US)} ${it.message}" }
    }

    fun clear(source: String? = null) {
        if (source == null) entries.clear() else entries.removeAll { it.source == source }
    }

    private companion object {
        const val MAX_ENTRIES = 600
        const val MAX_RETURNED = 250
        const val MAX_MESSAGE_CHARS = 1_000
    }
}
