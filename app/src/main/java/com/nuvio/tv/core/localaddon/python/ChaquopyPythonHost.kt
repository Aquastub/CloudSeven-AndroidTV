package com.nuvio.tv.core.localaddon.python

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.util.function.Consumer

/** Runs addon Python code in-process with Chaquopy. The interpreter starts the first time it is needed. */
class ChaquopyPythonHost(private val context: Context) : PythonHost {
    private val runtime: PyObject by lazy {
        synchronized(this) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context))
            Python.getInstance().getModule("nuvio_runtime")
        }
    }

    override val version: String?
        get() = runCatching { runtime.callAttr("version").toString() }.getOrNull()

    override fun run(request: PythonRunRequest, onLine: ((String) -> Unit)?): PythonRunResult {
        val sink: Consumer<String>? = onLine?.let { callback -> Consumer { line -> callback(line) } }
        val result = runtime.callAttr(
            "run",
            request.runId,
            request.scriptPath,
            request.code,
            JSONArray(request.args).toString(),
            JSONArray(request.paths).toString(),
            JSONObject(request.env).toString(),
            request.stdin,
            request.maxOutputChars,
            sink,
        ).asList()
        return PythonRunResult(
            exitCode = result[0].toInt(),
            stdout = result[1].toString(),
            stderr = result[2].toString(),
        )
    }

    override fun stop(runId: String) {
        runCatching { runtime.callAttr("stop", runId) }
    }
}
