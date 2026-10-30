package com.nuvio.tv.core.localaddon

import android.content.Context
import com.nuvio.tv.core.localaddon.python.PythonHost
import com.nuvio.tv.core.localaddon.python.PythonRunRequest
import com.nuvio.tv.core.localaddon.python.PythonRunResult
import com.nuvio.tv.core.localaddon.python.PythonRuntime
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * `nuvio.python` for local addons on Android TV: runs Python code or a .py file from the addon with the
 * embedded interpreter. Needs the "python" permission, which the user approves at install.
 * Background programs, bundled binaries and recording are not available on TV yet.
 */
@Singleton
class LocalAddonPrograms @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ZipAddonStore,
) {
    private val counter = AtomicLong()

    suspend fun call(slug: String, permissions: Set<String>, operation: String, payload: String): String {
        if (!operation.startsWith("python.")) {
            return when (operation) {
                "list" -> success { put("programs", JSONArray()) }
                else -> failure("Programs are not supported on Android TV yet.")
            }
        }
        if (PERMISSION_PYTHON !in permissions) return failure("This addon was not given the $PERMISSION_PYTHON permission.")
        return runCatching {
            when (operation) {
                "python.available" -> available()
                "python.run" -> run(slug, JSONObject(payload.ifBlank { "{}" }))
                else -> failure("This Python operation is not supported on Android TV yet.")
            }
        }.getOrElse { failure(it.message ?: "Python failed") }
    }

    private fun available(): String = success {
        val host = PythonRuntime.host
        put("available", host != null)
        host?.version?.let { put("version", it) }
    }

    private suspend fun run(slug: String, spec: JSONObject): String {
        val host: PythonHost = PythonRuntime.host ?: return failure("Python is not available in this build.")
        val addonDir = store.addonDir(slug)
        val data = store.dataDir(slug)
        val recordings = File(data, "recordings").also { it.mkdirs() }
        val tmp = File(data, "tmp").also { it.mkdirs() }

        val script = spec.optString("script").trim().ifBlank { null }
        val code = spec.optString("code").takeIf { it.isNotBlank() }
        if ((script == null) == (code == null)) {
            return failure("Give either \"script\" (a .py file in the addon) or \"code\".")
        }
        if (code != null && code.length > MAX_PYTHON_CODE_CHARS) {
            return failure("\"code\" is too long. Put it in a .py file in the addon.")
        }
        var scriptFile: File? = null
        if (script != null) {
            val file = File(addonDir, script)
            if (!file.canonicalPath.startsWith(addonDir.canonicalPath + File.separator) || !file.isFile) {
                return failure("The script \"$script\" was not found in the addon.")
            }
            scriptFile = file
        }

        fun expand(text: String) = text
            .replace("{recordings}", recordings.absolutePath)
            .replace("{data}", data.absolutePath)
            .replace("{tmp}", tmp.absolutePath)

        val rawArgs = spec.optJSONArray("args")
        val args = ArrayList<String>()
        if (rawArgs != null) {
            if (rawArgs.length() > 300) return failure("\"args\" must be a list of at most 300 short strings.")
            for (i in 0 until rawArgs.length()) {
                val value = rawArgs.opt(i) as? String ?: return failure("\"args\" must be a list of at most 300 short strings.")
                if (value.length > 8_000) return failure("\"args\" must be a list of at most 300 short strings.")
                args += expand(value)
            }
        }

        // Folders the script can import from: its own folder, the addon root and the usual library folders.
        val paths = buildList {
            scriptFile?.parentFile?.let { add(it.absolutePath) }
            add(addonDir.absolutePath)
            listOf("lib", "python", "site-packages").map { File(addonDir, it) }.filter { it.isDirectory }
                .forEach { add(it.absolutePath) }
        }.distinct()

        val env = LinkedHashMap<String, String>().apply {
            put("NUVIO_ADDON", addonDir.absolutePath)
            put("NUVIO_DATA", data.absolutePath)
            put("NUVIO_RECORDINGS", recordings.absolutePath)
            put("HOME", data.absolutePath)
            put("TMPDIR", tmp.absolutePath)
            spec.optJSONObject("env")?.let { raw ->
                raw.keys().forEach { key ->
                    val value = raw.opt(key) as? String ?: return@forEach
                    if (!ENV_NAME_REGEX.matches(key) || key == "LD_PRELOAD" || key == "LD_LIBRARY_PATH" || key.startsWith("PYTHON")) return@forEach
                    put(key, expand(value.take(1_000)))
                }
            }
        }

        val runId = "py-${counter.incrementAndGet()}"
        val request = PythonRunRequest(
            runId = runId,
            scriptPath = scriptFile?.absolutePath,
            code = code,
            args = args,
            paths = paths,
            env = env,
            stdin = spec.optString("stdin").takeIf { spec.has("stdin") }?.take(1_000_000),
            maxOutputChars = spec.optInt("maxOutputChars", 1_000_000).coerceIn(1_000, 8_000_000),
        )
        val timeoutMs = spec.optInt("timeoutMs", 60_000).coerceIn(1_000, 600_000).toLong()

        var result: PythonRunResult? = null
        var error: Throwable? = null
        val worker = thread(isDaemon = true, name = "python-$runId") {
            runCatching { host.run(request) }.onSuccess { result = it }.onFailure { error = it }
        }
        var timedOut = false
        try {
            runInterruptible(Dispatchers.IO) { worker.join(timeoutMs) }
            if (worker.isAlive) {
                timedOut = true
                host.stop(runId)
                runInterruptible(Dispatchers.IO) { worker.join(3_000) }
            }
        } finally {
            if (worker.isAlive) host.stop(runId) // cancelled while waiting
        }

        error?.let { return failure(it.message ?: "Python failed to start.") }
        val outcome = result ?: return failure("The script did not finish in time and could not be stopped.")
        return success {
            put("exitCode", outcome.exitCode)
            put("timedOut", timedOut)
            put("stdout", outcome.stdout)
            put("stderr", outcome.stderr.takeLast(8_000))
        }
    }

    private fun success(block: JSONObject.() -> Unit = {}): String =
        JSONObject().apply {
            put("ok", true)
            block()
        }.toString()

    private fun failure(message: String): String =
        JSONObject().apply {
            put("ok", false)
            put("error", message)
        }.toString()

    private companion object {
        const val MAX_PYTHON_CODE_CHARS = 200_000
        val ENV_NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
    }
}
