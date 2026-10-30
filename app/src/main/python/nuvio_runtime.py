"""Runs addon Python code for Nuvio, one run per call, with the output captured.

Called from Kotlin (ChaquopyPythonHost). Several runs can be active at once, each in its own thread;
`sys.stdout` / `sys.stderr` are routed per thread so their output does not mix.

Runs share the interpreter's process-wide state (working folder, environment, sys.argv), so a script
should take everything it needs from its arguments and from the NUVIO_* environment variables.
"""
import ctypes
import io
import json
import os
import runpy
import sys
import threading
import traceback

_lock = threading.Lock()
_sinks = {}    # thread id -> _Sink
_threads = {}  # run id -> thread id


class _Sink:
    def __init__(self, limit, on_line):
        self.limit = limit
        self.on_line = on_line
        self.size = 0
        self.out = []
        self.err = []
        self._partial = {"stdout": "", "stderr": ""}

    def write(self, name, text):
        if not text:
            return
        if self.size < self.limit:
            kept = text[: self.limit - self.size]
            (self.out if name == "stdout" else self.err).append(kept)
            self.size += len(kept)
        if self.on_line is not None:
            buffered = self._partial[name] + text
            *lines, rest = buffered.split("\n")
            self._partial[name] = rest
            for line in lines:
                self._emit(line)

    def finish(self):
        if self.on_line is not None:
            for rest in self._partial.values():
                self._emit(rest)

    def _emit(self, line):
        if line.strip():
            try:
                self.on_line.accept(line)
            except Exception:
                pass


class _Stream:
    """Stands in for sys.stdout / sys.stderr: output of a run goes to its sink, everything else to the original."""

    encoding = "utf-8"
    errors = "replace"

    def __init__(self, name, fallback):
        self._name = name
        self._fallback = fallback

    def write(self, text):
        sink = _sinks.get(threading.get_ident())
        if sink is None:
            return self._fallback.write(text)
        sink.write(self._name, text)
        return len(text)

    def flush(self):
        if _sinks.get(threading.get_ident()) is None:
            self._fallback.flush()

    def isatty(self):
        return False

    def writable(self):
        return True

    def __getattr__(self, attribute):
        return getattr(self._fallback, attribute)


def _install():
    with _lock:
        if not isinstance(sys.stdout, _Stream):
            sys.stdout = _Stream("stdout", sys.stdout)
            sys.stderr = _Stream("stderr", sys.stderr)


def version():
    return sys.version.split()[0]


def run(run_id, script, code, args_json, paths_json, env_json, stdin_text, limit, on_line):
    """Runs `script` (a file) or `code` (a string). Returns [exit_code, stdout, stderr]."""
    _install()
    ident = threading.get_ident()
    sink = _Sink(int(limit), on_line)
    with _lock:
        _sinks[ident] = sink
        _threads[run_id] = ident

    exit_code = 0
    try:
        sys.dont_write_bytecode = True  # keep the addon's folder clean
        args = json.loads(args_json or "[]")
        sys.argv = [script or "<code>"] + [str(a) for a in args]
        for path in reversed(json.loads(paths_json or "[]")):
            if path not in sys.path:
                sys.path.insert(0, path)
        for key, value in json.loads(env_json or "{}").items():
            os.environ[str(key)] = str(value)
        sys.stdin = io.StringIO(stdin_text or "")

        if script:
            runpy.run_path(script, run_name="__main__")
        else:
            exec(compile(code or "", "<addon>", "exec"), {"__name__": "__main__"})
    except SystemExit as stop:
        value = stop.code
        if value is None:
            exit_code = 0
        elif isinstance(value, int):
            exit_code = value
        else:
            sink.write("stderr", str(value) + "\n")
            exit_code = 1
    except BaseException:  # includes the KeyboardInterrupt that stop() raises
        traceback.print_exc()
        exit_code = 1
    finally:
        sink.finish()
        with _lock:
            _sinks.pop(ident, None)
            _threads.pop(run_id, None)
    return [exit_code, "".join(sink.out), "".join(sink.err)]


def stop(run_id):
    """Interrupts a running script (it is stopped the next time it executes Python code)."""
    with _lock:
        ident = _threads.get(run_id)
    if ident is None:
        return False
    changed = ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_ulong(ident), ctypes.py_object(KeyboardInterrupt))
    return changed == 1
