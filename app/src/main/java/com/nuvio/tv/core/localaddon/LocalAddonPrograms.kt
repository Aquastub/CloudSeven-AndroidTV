package com.nuvio.tv.core.localaddon

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import com.nuvio.tv.core.localaddon.program.AddonProgramService
import com.nuvio.tv.core.localaddon.program.AddonProgramState
import com.nuvio.tv.core.localaddon.program.HlsRecorder
import com.nuvio.tv.core.localaddon.program.RawStreamRecorder
import com.nuvio.tv.core.localaddon.python.PythonHost
import com.nuvio.tv.core.localaddon.python.PythonRunRequest
import com.nuvio.tv.core.localaddon.python.PythonRunResult
import com.nuvio.tv.core.localaddon.python.PythonRuntime
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * `nuvio.python` and `nuvio.programs` for local addons on Android TV.
 *
 *  - python.available / python.run / python.start: Python code or a .py file from the addon (needs "python").
 *  - start (built-in recorders "auto", "hls-record", "stream-record"), stop, list (need "programs").
 *
 * Running programs keep the app alive through a foreground service. Bundled native programs ("exec" and
 * addon-supplied binaries) are not supported on TV.
 */
@Singleton
class LocalAddonPrograms @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ZipAddonStore,
    private val diagnostics: LocalAddonDiagnostics,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val programs = ConcurrentHashMap<String, RunningProgram>()
    private val counter = AtomicLong()

    // Not the app's shared client: that one answers local addons, which would be a dependency cycle.
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private class RunningProgram(
        val id: String,
        val slug: String,
        val kind: String,
        val name: String,
        val baseName: String,
        val tag: String,
        val startedAt: Long,
        val directory: File,
    ) {
        @Volatile var running = true
        @Volatile var error: String? = null
        @Volatile var abort: (() -> Unit)? = null
        val logLines = ConcurrentLinkedDeque<String>()
        var job: Job? = null

        fun appendLog(line: String) {
            logLines.addLast(line.take(300))
            while (logLines.size > 20) logLines.pollFirst()
        }
    }

    init {
        AddonProgramState.stopAll = { stopAll() }
    }

    /** python.* needs "python"; stop and list work with either permission; the rest needs "programs". */
    private fun allowed(operation: String, permissions: Set<String>): Boolean = when {
        operation.startsWith("python.") -> PERMISSION_PYTHON in permissions
        operation == "stop" || operation == "list" -> PERMISSION_PROGRAMS in permissions || PERMISSION_PYTHON in permissions
        else -> PERMISSION_PROGRAMS in permissions
    }

    suspend fun call(slug: String, permissions: Set<String>, operation: String, payload: String): String =
        withContext(Dispatchers.IO) {
            if (!allowed(operation, permissions)) {
                val needed = if (operation.startsWith("python.")) PERMISSION_PYTHON else PERMISSION_PROGRAMS
                return@withContext failure("This addon was not given the $needed permission.")
            }
            val spec = runCatching { JSONObject(payload.ifBlank { "{}" }) }.getOrDefault(JSONObject())
            try {
                when (operation) {
                    "python.available" -> pythonAvailable()
                    "python.run" -> pythonRun(slug, spec)
                    "python.start" -> pythonStart(slug, spec)
                    "available" -> success { put("programs", JSONArray()) }
                    "start" -> start(slug, spec)
                    "exec" -> failure("Running bundled programs is not supported on Android TV.")
                    "stop" -> stop(slug, spec)
                    "list" -> list(slug)
                    else -> failure("Unknown operation \"$operation\".")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Program call failed", error)
                failure(error.message ?: "Something went wrong.")
            }
        }

    // ------------------------------------------------------------------ python

    private fun pythonAvailable(): String = success {
        val host = PythonRuntime.host
        put("available", host != null)
        host?.version?.let { put("version", it) }
    }

    /** Checks and expands a python.run / python.start request. */
    private fun prepare(slug: String, spec: JSONObject, runId: String): Result<PythonRunRequest> {
        PythonRuntime.host ?: return Result.failure(IllegalStateException("Python is not available in this build."))
        val addonDir = store.addonDir(slug)
        val data = store.dataDir(slug)
        val recordings = recordingsDirectory()
        val tmp = File(data, "tmp").also { it.mkdirs() }

        val script = spec.optString("script").trim().ifBlank { null }
        val code = spec.optString("code").takeIf { it.isNotBlank() }
        if ((script == null) == (code == null)) {
            return Result.failure(IllegalArgumentException("Give either \"script\" (a .py file in the addon) or \"code\"."))
        }
        if (code != null && code.length > MAX_PYTHON_CODE_CHARS) {
            return Result.failure(IllegalArgumentException("\"code\" is too long. Put it in a .py file in the addon."))
        }
        var scriptFile: File? = null
        if (script != null) {
            val file = File(addonDir, script)
            if (!file.canonicalPath.startsWith(addonDir.canonicalPath + File.separator) || !file.isFile) {
                return Result.failure(IllegalArgumentException("The script \"$script\" was not found in the addon."))
            }
            scriptFile = file
        }

        fun expand(text: String) = text
            .replace("{recordings}", recordings.absolutePath)
            .replace("{data}", data.absolutePath)
            .replace("{tmp}", tmp.absolutePath)

        val argsError = IllegalArgumentException("\"args\" must be a list of at most 300 short strings.")
        val rawArgs = spec.optJSONArray("args")
        val args = ArrayList<String>()
        if (rawArgs != null) {
            if (rawArgs.length() > 300) return Result.failure(argsError)
            for (i in 0 until rawArgs.length()) {
                val value = rawArgs.opt(i) as? String ?: return Result.failure(argsError)
                if (value.length > 8_000) return Result.failure(argsError)
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

        return Result.success(
            PythonRunRequest(
                runId = runId,
                scriptPath = scriptFile?.absolutePath,
                code = code,
                args = args,
                paths = paths,
                env = env,
                stdin = if (spec.has("stdin")) spec.optString("stdin").take(1_000_000) else null,
                maxOutputChars = spec.optInt("maxOutputChars", 1_000_000).coerceIn(1_000, 8_000_000),
            ),
        )
    }

    private suspend fun pythonRun(slug: String, spec: JSONObject): String {
        val host: PythonHost = PythonRuntime.host ?: return failure("Python is not available in this build.")
        val runId = "py-${counter.incrementAndGet()}"
        val request = prepare(slug, spec, runId).getOrElse { return failure(it.message ?: "Invalid request.") }
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
        if (outcome.stderr.isNotBlank()) diagnostics.record(slug, "python stderr: ${outcome.stderr.takeLast(400)}", "warn")
        return success {
            put("exitCode", outcome.exitCode)
            put("timedOut", timedOut)
            put("stdout", outcome.stdout)
            put("stderr", outcome.stderr.takeLast(8_000))
        }
    }

    /** Starts a Python script in the background (it keeps running after the addon call returns). */
    private fun pythonStart(slug: String, spec: JSONObject): String {
        val host: PythonHost = PythonRuntime.host ?: return failure("Python is not available in this build.")
        val tag = spec.optString("tag").takeIf { it.isNotBlank() }?.take(500) ?: spec.optString("script").ifBlank { "python" }
        programs.values.firstOrNull { it.slug == slug && it.running && it.tag == tag }?.let { existing ->
            return success {
                put("id", existing.id)
                put("already", true)
            }
        }
        if (programs.values.count { it.slug == slug && it.running } >= MAX_PER_ADDON) {
            return failure("Too many programs are already running for this addon.")
        }

        val id = "$slug-${counter.incrementAndGet()}"
        val request = prepare(slug, spec, id).getOrElse { return failure(it.message ?: "Invalid request.") }
        val maxMillis = spec.optInt("maxMinutes", 360).coerceIn(1, 1_440) * 60_000L
        val name = spec.optString("name").trim().ifBlank { "Python" }

        val program = RunningProgram(id, slug, "python", name, name, tag, System.currentTimeMillis(), recordingsDirectory())
        register(program)

        program.job = scope.launch {
            var result: PythonRunResult? = null
            var failed: Throwable? = null
            val worker = thread(isDaemon = true, name = "python-${program.id}") {
                runCatching { host.run(request) { line -> log(program, line) } }
                    .onSuccess { result = it }
                    .onFailure { failed = it }
            }
            program.abort = { host.stop(program.id) }
            val startedAt = System.currentTimeMillis()
            try {
                while (worker.isAlive) {
                    if (System.currentTimeMillis() - startedAt >= maxMillis) {
                        log(program, "Stopped: reached the time limit")
                        host.stop(program.id)
                        break
                    }
                    delay(500)
                }
            } finally {
                if (worker.isAlive) {
                    host.stop(program.id)
                    withContext(NonCancellable + Dispatchers.IO) { worker.join(5_000) }
                }
                failed?.let { program.error = it.message ?: it.toString() }
                result?.let { done ->
                    if (done.exitCode != 0 && program.error == null) {
                        program.error = "Exited with code ${done.exitCode}: ${done.stderr.lines().lastOrNull { it.isNotBlank() }.orEmpty()}"
                    }
                }
                finish(program)
            }
        }
        return success {
            put("id", program.id)
            put("program", "python")
        }
    }

    // --------------------------------------------------------------- recorders

    private fun start(slug: String, spec: JSONObject): String {
        val requested = spec.optString("program").trim().ifBlank { "auto" }
        if (requested !in BUILT_IN_PROGRAMS) {
            return failure("Only the built-in recorders (\"auto\", \"hls-record\", \"stream-record\") are available on Android TV.")
        }
        val input = spec.optString("input").trim()
        if (!input.startsWith("http://", ignoreCase = true) && !input.startsWith("https://", ignoreCase = true)) {
            return failure("\"input\" must be an http(s) URL.")
        }

        val tag = spec.optString("tag").takeIf { it.isNotBlank() }?.take(500) ?: input
        programs.values.firstOrNull { it.slug == slug && it.running && it.tag == tag }?.let { existing ->
            return success {
                put("id", existing.id)
                put("already", true)
            }
        }
        if (programs.values.count { it.slug == slug && it.running } >= MAX_PER_ADDON) {
            return failure("Too many programs are already running for this addon.")
        }

        val kind = if (requested == "auto") {
            if (input.substringBefore('?').contains(".m3u8", ignoreCase = true)) "hls-record" else "stream-record"
        } else {
            requested
        }
        val recordings = recordingsDirectory()
        val userAgent = spec.optString("userAgent").takeIf { USER_AGENT_REGEX.matches(it) } ?: DEFAULT_USER_AGENT
        val segmentMillis = spec.optInt("segmentMinutes", 30).coerceIn(1, 240) * 60_000L
        val maxMillis = spec.optInt("maxMinutes", 360).coerceIn(1, 1_440) * 60_000L
        val name = spec.optString("name").trim().ifBlank { "Recording" }
        val baseName = name.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().take(60).ifBlank { "Recording" }

        val program = RunningProgram("$slug-${counter.incrementAndGet()}", slug, kind, name, baseName, tag, System.currentTimeMillis(), recordings)
        register(program)

        program.job = scope.launch {
            try {
                val onLog: (String) -> Unit = { line -> log(program, line) }
                if (kind == "hls-record") {
                    val recorder = HlsRecorder(client, input, userAgent, recordings, baseName, segmentMillis, maxMillis, onLog)
                    program.abort = { recorder.abort() }
                    recorder.run()
                } else {
                    val recorder = RawStreamRecorder(client, input, userAgent, recordings, baseName, segmentMillis, maxMillis, onLog)
                    program.abort = { recorder.abort() }
                    recorder.run()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                program.error = error.message ?: error.toString()
                Log.w(TAG, "Program ${program.id} failed", error)
            } finally {
                finish(program)
            }
        }
        return success {
            put("id", program.id)
            put("program", kind)
            put("directory", recordings.absolutePath)
        }
    }

    private fun stop(slug: String, spec: JSONObject): String {
        val program = programs[spec.optString("id")]?.takeIf { it.slug == slug } ?: return failure("No such program.")
        program.abort?.invoke()
        program.job?.cancel()
        return success { put("id", program.id) }
    }

    private fun stopAll() {
        programs.values.filter { it.running }.forEach { program ->
            program.abort?.invoke()
            program.job?.cancel()
        }
    }

    private fun list(slug: String): String = success {
        put(
            "programs",
            JSONArray().also { array ->
                programs.values.filter { it.slug == slug }.sortedBy { it.startedAt }.forEach { program ->
                    array.put(
                        JSONObject().apply {
                            put("id", program.id)
                            put("program", program.kind)
                            put("name", program.name)
                            put("tag", program.tag)
                            put("running", program.running)
                            put("startedAt", program.startedAt)
                            program.error?.let { put("error", it) }
                            if (program.logLines.isNotEmpty()) put("log", program.logLines.joinToString("\n").takeLast(2_000))
                            if (program.kind != "python") put("files", JSONArray(recordedFiles(program).map { it.absolutePath }))
                        },
                    )
                }
            },
        )
    }

    // ----------------------------------------------------------------- helpers

    private fun register(program: RunningProgram) {
        programs[program.id] = program
        // Keep the list short: only the 20 most recent finished programs per addon are remembered.
        programs.values.filter { it.slug == program.slug && !it.running }.sortedBy { it.startedAt }
            .dropLast(20).forEach { programs.remove(it.id) }
        AddonProgramState.activeCount.update { it + 1 }
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, AddonProgramService::class.java))
        }.onFailure { Log.w(TAG, "Could not start the foreground service; the program still runs while the app is open", it) }
        diagnostics.record("programs", "${program.name} started (${program.kind})")
    }

    private fun finish(program: RunningProgram) {
        program.running = false
        program.abort = null
        AddonProgramState.activeCount.update { (it - 1).coerceAtLeast(0) }
        diagnostics.record("programs", "${program.name} ended" + (program.error?.let { ": $it" } ?: ""), if (program.error != null) "warn" else "info")
    }

    private fun log(program: RunningProgram, line: String) {
        program.appendLog(line)
        diagnostics.record("programs", "${program.name}: $line")
    }

    private fun recordedFiles(program: RunningProgram): List<File> =
        program.directory.listFiles { file ->
            file.isFile && file.name.startsWith("${program.baseName}-") && file.lastModified() >= program.startedAt - 2_000
        }?.sortedBy { it.name }.orEmpty()

    private fun recordingsDirectory(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return File(base, "NuvioRecordings").also { it.mkdirs() }
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
        const val TAG = "LocalAddonPrograms"
        const val MAX_PYTHON_CODE_CHARS = 200_000
        const val MAX_PER_ADDON = 4
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
        val USER_AGENT_REGEX = Regex("^[\\x20-\\x7E]{1,200}$")
        val ENV_NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
        val BUILT_IN_PROGRAMS = setOf("auto", "hls-record", "stream-record")
    }
}
