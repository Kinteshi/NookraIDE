#!/usr/bin/env -S uv run --quiet --script
# /// script
# requires-python = ">=3.9"
# dependencies = ["jupyter_client>=8"]
# ///
"""Jupyter kernel bridge for the Nookra IDE native IDE.

Speaks newline-delimited JSON-RPC 2.0 on stdin/stdout (one bridge per notebook) and drives a
single kernel through jupyter_client. Kernels come from the user's own environments (conda,
venvs, system Python with ipykernel), so nothing is installed into them.

Requests:  kernelspecs, start {name, cwd, plotly_display_ids}, execute {code, cell_id}, complete {code, cursor_pos},
           comm_msg {comm_id, data}, interrupt, restart, shutdown
Notifications to the client: output {cell_id, output, display_id}, clear_output {cell_id, wait},
           update_display {display_id, output}, status {state},
           comm_open {comm_id, target_name, data, buffers}, comm_msg {comm_id, data, buffers},
           comm_close {comm_id}, comms_reset, widget_output {comm_id, output},
           widget_clear_output {comm_id, wait}

ipywidgets: the kernel's widget models arrive as comms (target "jupyter.widget") and the client
sends their changes back with comm_msg. One reader takes every iopub message: a cell's own go
to its execute; outputs an Output widget is capturing (its `msg_id` is their parent's, as in
JupyterLab) go to that widget instead, also those of callbacks run after the cell finished.
"""

import base64

import glob
import json
import os
import queue
import shutil
import subprocess
import sys
import threading
import traceback

from jupyter_client import KernelManager
from jupyter_client.kernelspec import KernelSpecManager

_write_lock = threading.Lock()


def send(message):
    line = json.dumps(message, separators=(",", ":"), default=str)
    with _write_lock:
        sys.stdout.write(line + "\n")
        sys.stdout.flush()


def notify(method, params):
    send({"jsonrpc": "2.0", "method": method, "params": params})


def respond(request_id, result=None, error=None):
    message = {"jsonrpc": "2.0", "id": request_id}
    if error is None:
        message["result"] = result
    else:
        message["error"] = {"code": -32000, "message": error}
    send(message)


def candidate_prefixes():
    home = os.path.expanduser("~")
    roots = [os.path.join(home, name) for name in ("miniconda3", "anaconda3", "miniforge3", "mambaforge", ".venv")]
    roots += [os.path.join(os.getcwd(), name) for name in (".venv", "venv")]
    prefixes = []
    for root in roots:
        if os.path.isdir(root):
            prefixes.append(root)
            prefixes.extend(sorted(glob.glob(os.path.join(root, "envs", "*"))))
    return prefixes


def interpreter_has_ipykernel(python):
    try:
        return subprocess.run(
            [python, "-c", "import ipykernel"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=20,
        ).returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def generated_kernel_dir(missing):
    """Kernelspecs for interpreters that have ipykernel but never registered one.

    Interpreters without ipykernel are appended to ``missing`` so the app can offer to install it.
    """
    target = os.path.join(os.path.expanduser("~"), ".local", "share", "avf-studio", "kernels")
    pythons = [os.path.join(prefix, "bin", "python") for prefix in candidate_prefixes()]
    # Not shutil.which: under `uv run` the first python3 on PATH is the bridge's own environment.
    system_python = next((p for p in ("/usr/bin/python3", "/usr/local/bin/python3") if os.path.exists(p)), None)
    if system_python:
        pythons.append(system_python)
    prune_generated_specs(target)
    for python in pythons:
        if not os.path.exists(python):
            continue
        label = os.path.basename(os.path.dirname(os.path.dirname(python))) if "/bin/" in python else "system"
        if python == system_python:
            label = "system"
        if not interpreter_has_ipykernel(python):
            missing.append({"python": python, "label": label})
            continue
        name = "avf-" + "".join(ch if ch.isalnum() else "-" for ch in label.lower())
        directory = os.path.join(target, name)
        os.makedirs(directory, exist_ok=True)
        spec = {
            "argv": [python, "-m", "ipykernel_launcher", "-f", "{connection_file}"],
            "display_name": "Python (%s)" % label,
            "language": "python",
        }
        with open(os.path.join(directory, "kernel.json"), "w", encoding="utf-8") as handle:
            json.dump(spec, handle)
    return target


def prune_generated_specs(target):
    """Removes the bridge's own kernelspecs whose interpreter is gone (a deleted venv or env)."""
    for kernel_json in glob.glob(os.path.join(target, "avf-*", "kernel.json")):
        try:
            with open(kernel_json, encoding="utf-8") as handle:
                argv = json.load(handle).get("argv") or []
        except (OSError, ValueError):
            argv = []
        if not argv or not os.path.exists(argv[0]):
            shutil.rmtree(os.path.dirname(kernel_json), ignore_errors=True)


def interpreter_missing(argv):
    """A spec that names an interpreter by absolute path that no longer exists."""
    return bool(argv) and os.path.isabs(argv[0]) and not os.path.exists(argv[0])


def resolve_argv(argv, resource_dir):
    """ipykernel's own kernel.json says just "python"; under `uv run` that would be the bridge's
    interpreter, which has no ipykernel. Use the interpreter of the prefix the spec belongs to."""
    if not argv or os.path.isabs(argv[0]) or "/" in argv[0]:
        return argv
    # <prefix>/share/jupyter/kernels/<name>
    prefix = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(resource_dir))))
    candidate = os.path.join(prefix, "bin", argv[0])
    return [candidate] + list(argv[1:]) if os.path.exists(candidate) else argv


class ResolvingSpecManager(KernelSpecManager):
    def get_kernel_spec(self, kernel_name):
        spec = super().get_kernel_spec(kernel_name)
        spec.argv = resolve_argv(spec.argv, spec.resource_dir)
        return spec


def spec_manager(missing):
    manager = ResolvingSpecManager()
    extra = [os.path.join(prefix, "share", "jupyter", "kernels") for prefix in candidate_prefixes()]
    extra.append(generated_kernel_dir(missing))
    manager.kernel_dirs = list(dict.fromkeys(manager.kernel_dirs + [d for d in extra if os.path.isdir(d)]))
    return manager


# Plotly figures display themselves through _ipython_display_, which ignores display_id:
# `handle = display(fig, display_id=True)` then `handle.update(fig)` adds a new figure each
# time. Without that hook IPython uses the figure's mime bundle, which keeps the id. Only when
# plotly is installed in the kernel's environment; importing it costs a moment at start.
PLOTLY_DISPLAY_IDS = """\
def _avf_plotly_display_ids():
    import importlib.util
    if importlib.util.find_spec("plotly") is None:
        return
    from plotly.basedatatypes import BaseFigure
    if "_ipython_display_" in vars(BaseFigure) and hasattr(BaseFigure, "_repr_mimebundle_"):
        del BaseFigure._ipython_display_
try:
    _avf_plotly_display_ids()
except Exception:
    pass
del _avf_plotly_display_ids
"""


# The Variables view: defined in the kernel by a silent execute (no history, no output) before
# each look, so a restarted kernel needs nothing else. The results come back as user_expressions
# whose repr is the JSON itself. Names starting with "_" are left out, so these two never show.
VARIABLES_HELPER = """\
class _AvfJson:
    def __init__(self, text):
        self.text = text

    def __repr__(self):
        return self.text


def _avf_variables(limit=500):
    import json, reprlib, types
    ip = get_ipython()
    hidden = getattr(ip, "user_ns_hidden", {})
    skip = (types.ModuleType, types.FunctionType, types.BuiltinFunctionType, types.MethodType, type)
    short = reprlib.Repr()
    short.maxstring = short.maxother = 160
    short.maxlist = short.maxtuple = short.maxset = short.maxdict = 12
    rows = []
    names = sorted((n for n in list(ip.user_ns) if not n.startswith("_") and n not in hidden), key=str.lower)
    for name in names:
        value = ip.user_ns.get(name)
        if isinstance(value, skip) or name in ("In", "Out", "exit", "quit", "get_ipython"):
            continue
        if len(rows) >= limit:
            return _AvfJson(json.dumps({"variables": rows, "truncated": True}))
        kind = type(value)
        module = getattr(kind, "__module__", "") or ""
        size = ""
        viewable = False
        try:
            shape = getattr(value, "shape", None)
            if isinstance(shape, tuple) and all(isinstance(n, int) for n in shape):
                size = " \\u00d7 ".join(str(n) for n in shape) or "scalar"
                viewable = (module.startswith("pandas") and kind.__name__ in ("DataFrame", "Series")) or (
                    module.startswith("numpy") and kind.__name__ == "ndarray" and 1 <= len(shape) <= 2)
            elif hasattr(value, "__len__") and not isinstance(value, (str, bytes)):
                size = str(len(value))
            elif isinstance(value, (str, bytes)):
                size = str(len(value))
        except Exception:
            pass
        try:
            if viewable and kind.__name__ == "DataFrame":
                text = "columns: " + ", ".join(str(c) for c in list(value.columns)[:20])
            elif viewable:
                text = "dtype: " + str(value.dtype)
            else:
                text = short.repr(value)
        except Exception as error:
            text = "<repr failed: %s>" % type(error).__name__
        rows.append({"name": name, "type": kind.__name__, "module": module, "size": size, "value": text, "viewable": viewable})
    return _AvfJson(json.dumps({"variables": rows, "truncated": False}))


def _avf_filter(frame, query):
    # Rows matching [query]: a pandas query (`price > 10 and city == "Rome"`) when it reads as
    # one, else rows where any cell (or the index) contains the text, ignoring case.
    error = ""
    if any(op in query for op in ("==", "!=", ">", "<", " and ", " or ", "not ", " in ", "`", "&", "|", "~")):
        try:
            return frame.query(query, engine="python"), ""
        except Exception as failure:
            error = "%s: %s" % (type(failure).__name__, failure)
    needle = query.lower()
    mask = frame.astype(str).apply(lambda column: column.str.lower().str.contains(needle, regex=False)).any(axis=1)
    mask = mask | frame.index.astype(str).str.lower().str.contains(needle, regex=False)
    return frame[mask], error


def _avf_table(name, limit=1000, offset=0, query="", sort=None, ascending=True):
    import json, math
    value = get_ipython().user_ns[name]
    kind = type(value).__name__
    def cell(item):
        if isinstance(item, float) and (math.isnan(item) or math.isinf(item)):
            return str(item)
        return item if isinstance(item, (int, float, bool)) or item is None else str(item)
    if kind == "Series":
        value = value.to_frame()
        kind = "DataFrame"
    if kind != "DataFrame" and getattr(value, "ndim", 0) in (1, 2):
        try:
            import pandas
            value = pandas.DataFrame(value)
            kind = "DataFrame"
        except ImportError:
            pass
    if kind == "DataFrame":
        frame, error = (value, "")
        if query:
            frame, error = _avf_filter(frame, query)
        # Column 0 is the index, as the viewer shows it.
        if sort is not None and 0 <= sort <= len(frame.columns):
            if sort == 0:
                frame = frame.sort_index(ascending=ascending, kind="stable")
            else:
                frame = frame.sort_values(by=frame.columns[sort - 1], ascending=ascending, kind="stable", na_position="last")
        head = frame.iloc[offset:offset + limit]
        return _AvfJson(json.dumps({
            "columns": [str(c) for c in head.columns],
            "index_name": str(head.index.name) if head.index.name is not None else "",
            "index": [str(i) for i in head.index],
            "rows": [[cell(x) for x in row] for row in head.itertuples(index=False, name=None)],
            "total": int(len(value)),
            "matched": int(len(frame)),
            "offset": int(offset),
            "error": error,
        }, default=str))
    rows = value[:limit].tolist()
    if value.ndim == 1:
        rows = [[x] for x in rows]
    return _AvfJson(json.dumps({
        "columns": [str(c) for c in range(len(rows[0]) if rows else 0)],
        "index_name": "",
        "index": [str(i) for i in range(len(rows))],
        "rows": [[cell(x) for x in row] for row in rows],
        "total": int(value.shape[0]),
    }, default=str))
"""


def to_nbformat_output(message):
    kind = message["header"]["msg_type"]
    content = message["content"]
    if kind == "stream":
        return {"output_type": "stream", "name": content.get("name", "stdout"), "text": content.get("text", "")}
    if kind == "execute_result":
        return {
            "output_type": "execute_result",
            "data": content.get("data", {}),
            "metadata": content.get("metadata", {}),
            "execution_count": content.get("execution_count"),
        }
    if kind in ("display_data", "update_display_data"):
        return {"output_type": "display_data", "data": content.get("data", {}), "metadata": content.get("metadata", {})}
    if kind == "error":
        return {
            "output_type": "error",
            "ename": content.get("ename", ""),
            "evalue": content.get("evalue", ""),
            "traceback": content.get("traceback", []),
        }
    return None


IGNORED_IOPUB = ("status", "execute_input", "shutdown_reply")


class Bridge:
    def __init__(self):
        self.specs = None
        self.kernel = None
        self.client = None
        self.work = queue.Queue()
        self.handlers = []
        # The running execute: its message id and the queue its iopub messages go to.
        self.current = None
        # Widget comms: model names by comm id, and the Output widgets capturing (msg_id -> comm id).
        self.widget_models = {}
        self.capturing = {}
        self.reader = None
        threading.Thread(target=self._run_work, daemon=True).start()

    def _start_reader(self):
        client = self.client
        self.reader = threading.Thread(target=self._read_iopub, args=(client,), daemon=True)
        self.reader.start()

    def _read_iopub(self, client):
        while self.client is client:
            try:
                message = client.get_iopub_msg(timeout=0.5)
            except queue.Empty:
                continue
            except Exception:  # noqa: BLE001 - channels closing at shutdown
                if self.client is not client:
                    return
                continue
            try:
                self._dispatch(message)
            except Exception:  # noqa: BLE001 - one bad message must not stop the reader
                traceback.print_exc(file=sys.stderr)

    def _dispatch(self, message):
        kind = message["header"]["msg_type"]
        content = message.get("content", {})
        parent = message.get("parent_header", {}).get("msg_id")
        if kind in ("comm_open", "comm_msg", "comm_close"):
            self._comm(kind, content, message.get("buffers") or [])
            return
        comm_id = self.capturing.get(parent) if parent else None
        if comm_id is not None and kind not in IGNORED_IOPUB:
            if kind == "clear_output":
                notify("widget_clear_output", {"comm_id": comm_id, "wait": content.get("wait", False)})
            else:
                output = to_nbformat_output(message)
                if output is not None:
                    notify("widget_output", {"comm_id": comm_id, "output": output})
            return
        current = self.current
        if current is not None and parent == current[0]:
            current[1].put(message)

    def _comm(self, kind, content, buffers):
        comm_id = content.get("comm_id")
        data = content.get("data", {})
        encoded = [base64.b64encode(bytes(buffer)).decode("ascii") for buffer in buffers]
        if kind == "comm_open":
            if content.get("target_name") == "jupyter.widget":
                self.widget_models[comm_id] = data.get("state", {}).get("_model_name")
                self._track_capture(comm_id, data.get("state", {}))
            notify("comm_open", {
                "comm_id": comm_id, "target_name": content.get("target_name"), "data": data, "buffers": encoded,
            })
        elif kind == "comm_msg":
            if data.get("method") in ("update", "echo_update"):
                self._track_capture(comm_id, data.get("state", {}))
            notify("comm_msg", {"comm_id": comm_id, "data": data, "buffers": encoded})
        else:
            self.widget_models.pop(comm_id, None)
            self.capturing = {msg: comm for msg, comm in self.capturing.items() if comm != comm_id}
            notify("comm_close", {"comm_id": comm_id})

    def _track_capture(self, comm_id, state):
        """An Output widget's `msg_id` says whose outputs it takes (empty: none)."""
        if self.widget_models.get(comm_id) != "OutputModel" or "msg_id" not in state:
            return
        self.capturing = {msg: comm for msg, comm in self.capturing.items() if comm != comm_id}
        if state["msg_id"]:
            self.capturing[state["msg_id"]] = comm_id

    def comm_msg(self, params):
        """A widget change from the client (a slider moved, a button clicked, or binary buffers)."""
        if self.client is None:
            raise RuntimeError("Start a kernel first")
        message = self.client.session.msg("comm_msg", {"comm_id": params["comm_id"], "data": params.get("data", {})})
        raw_buffers = params.get("buffers") or []
        if raw_buffers:
            message["buffers"] = [base64.b64decode(item) for item in raw_buffers]
        self.client.shell_channel.send(message)
        return {"msg_id": message["header"]["msg_id"]}

    def _reset_comms(self):
        self.widget_models = {}
        self.capturing = {}
        notify("comms_reset", {})

    def kernelspecs(self, _params):
        missing = []
        self.specs = spec_manager(missing)
        result = []
        for name, entry in sorted(self.specs.get_all_specs().items()):
            spec = entry.get("spec", {})
            argv = resolve_argv(spec.get("argv") or [], entry.get("resource_dir", ""))
            # Specs left behind by deleted environments (the user's own are hidden, not removed).
            if interpreter_missing(argv):
                continue
            result.append({
                "name": name,
                "display_name": spec.get("display_name", name),
                "language": spec.get("language", ""),
                # The interpreter, so the app can offer the same environments for debugging.
                "python": argv[0] if argv and "python" in os.path.basename(argv[0]) else None,
            })
        return {"kernelspecs": result, "installable": missing}

    def start(self, params):
        if self.kernel is not None:
            raise RuntimeError("A kernel is already running in this bridge")
        self.specs = self.specs or spec_manager([])
        # IPC keeps kernel traffic on private Unix sockets instead of loopback TCP.
        runtime = os.path.join(os.path.expanduser("~"), ".local", "share", "avf-studio", "kernel-runtime")
        os.makedirs(runtime, mode=0o700, exist_ok=True)
        manager = KernelManager(
            kernel_name=params["name"],
            kernel_spec_manager=self.specs,
            transport="ipc",
            ip=os.path.join(runtime, "kernel-%d" % os.getpid()),
        )
        env = dict(os.environ)
        # Plotly's default in a plain kernel adds the "notebook" renderer, which embeds all of
        # plotly.js (megabytes) in the first output; the app draws the figure JSON itself.
        env.setdefault("PLOTLY_RENDERER", "plotly_mimetype")
        manager.start_kernel(cwd=params.get("cwd") or os.getcwd(), env=env)
        client = manager.client()
        client.start_channels()
        client.wait_for_ready(timeout=120)
        self.kernel, self.client = manager, client
        self._start_reader()
        spec = self.specs.get_kernel_spec(params["name"])
        self.plotly_display_ids = bool(params.get("plotly_display_ids")) and spec.language == "python"
        self.language = spec.language
        self._prepare_kernel()
        return {"name": params["name"]}

    def _prepare_kernel(self):
        """Runs the bridge's own setup in a fresh kernel, silently and before any cell."""
        if not getattr(self, "plotly_display_ids", False):
            return
        msg_id = self.client.execute(PLOTLY_DISPLAY_IDS, silent=True, store_history=False, allow_stdin=False)
        reply = self.client.get_shell_msg(timeout=60)
        while reply.get("parent_header", {}).get("msg_id") != msg_id:
            reply = self.client.get_shell_msg(timeout=60)

    def execute(self, params, request_id):
        if self.client is None:
            raise RuntimeError("Start a kernel first")
        cell_id = params.get("cell_id")
        # Built here so the reader knows its id before the kernel's first reply can arrive.
        request = self.client.session.msg("execute_request", {
            "code": params["code"], "silent": False, "store_history": True, "user_expressions": {},
            "allow_stdin": False, "stop_on_error": True,
        })
        msg_id = request["header"]["msg_id"]
        messages = queue.Queue()
        self.current = (msg_id, messages)
        self.client.shell_channel.send(request)
        notify("status", {"state": "busy"})
        while True:
            message = messages.get()
            kind = message["header"]["msg_type"]
            if kind == "status" and message["content"].get("execution_state") == "idle":
                break
            if kind == "clear_output":
                notify("clear_output", {"cell_id": cell_id, "wait": message["content"].get("wait", False)})
                continue
            output = to_nbformat_output(message)
            if output is None:
                continue
            # display(..., display_id=True) handles: the id is transient (not saved in the notebook);
            # an update replaces every output shown with that id, in whichever cell it is.
            display_id = message["content"].get("transient", {}).get("display_id")
            if kind == "update_display_data":
                if display_id:
                    notify("update_display", {"display_id": display_id, "output": output})
                continue
            notify("output", {"cell_id": cell_id, "output": output, "display_id": display_id})
        self.current = None
        reply = self.client.get_shell_msg(timeout=30)
        while reply.get("parent_header", {}).get("msg_id") != msg_id:
            reply = self.client.get_shell_msg(timeout=30)
        notify("status", {"state": "idle"})
        content = reply["content"]
        respond(request_id, {"status": content.get("status"), "execution_count": content.get("execution_count")})

    def inspect(self, expression):
        """Evaluates [expression] after defining the Variables helper, silently, and returns its JSON."""
        if self.client is None:
            raise RuntimeError("Start a kernel first")
        if getattr(self, "language", "python") != "python":
            return {"unsupported": True}
        msg_id = self.client.execute(
            VARIABLES_HELPER, silent=True, store_history=False, allow_stdin=False,
            user_expressions={"result": expression},
        )
        reply = self.client.get_shell_msg(timeout=60)
        while reply.get("parent_header", {}).get("msg_id") != msg_id:
            reply = self.client.get_shell_msg(timeout=60)
        content = reply["content"]
        if content.get("status") != "ok":
            raise RuntimeError(content.get("evalue") or "The kernel could not list its variables")
        result = content.get("user_expressions", {}).get("result", {})
        if result.get("status") != "ok":
            raise RuntimeError("%s: %s" % (result.get("ename", "Error"), result.get("evalue", "")))
        return json.loads(result.get("data", {}).get("text/plain", "null"))

    def variables(self, _params):
        return self.inspect("_avf_variables()")

    def table(self, params):
        sort = params.get("sort")
        return self.inspect("_avf_table(%r, %d, %d, %r, %r, %r)" % (
            str(params["name"]),
            int(params.get("limit", 1000)),
            max(0, int(params.get("offset", 0))),
            str(params.get("query") or ""),
            int(sort) if sort is not None else None,
            bool(params.get("ascending", True)),
        ))

    def complete(self, params):
        if self.client is None:
            return {"matches": []}
        reply = self.client.complete(params["code"], params.get("cursor_pos"), reply=True, timeout=10)
        content = reply["content"]
        return {
            "matches": content.get("matches", []),
            "cursor_start": content.get("cursor_start"),
            "cursor_end": content.get("cursor_end"),
        }

    def interrupt(self, _params):
        if self.kernel is not None:
            self.kernel.interrupt_kernel()
        return None

    def restart(self, _params):
        if self.kernel is None:
            raise RuntimeError("No kernel is running")
        self.kernel.restart_kernel(now=True)
        self.client.wait_for_ready(timeout=120)
        # The old kernel's widgets are gone with it.
        self._reset_comms()
        self._prepare_kernel()
        return None

    def shutdown(self, _params):
        if self.client is not None:
            self.client.stop_channels()
        if self.kernel is not None:
            self.kernel.shutdown_kernel(now=False)
        self.kernel = self.client = None
        return None

    def _run_work(self):
        # One at a time, in order: a look at the variables waits for the cells before it, and the
        # shell replies of two requests never interleave.
        while True:
            method, request_id, params = self.work.get()
            try:
                if method == "execute":
                    self.execute(params, request_id)
                else:
                    respond(request_id, getattr(self, method)(params))
            except Exception as error:  # noqa: BLE001 - reported to the client
                respond(request_id, error=str(error) or error.__class__.__name__)

    def handle(self, message):
        method = message.get("method")
        request_id = message.get("id")
        params = message.get("params") or {}
        # comm_msg too: the shell socket is the work thread's, and the kernel handles it after the cell anyway.
        if method in ("execute", "variables", "table", "comm_msg"):
            self.work.put((method, request_id, params))
            return
        handler = {
            "kernelspecs": self.kernelspecs,
            "start": self.start,
            "complete": self.complete,
            "interrupt": self.interrupt,
            "restart": self.restart,
            "shutdown": self.shutdown,
        }.get(method)
        if handler is None:
            if request_id is not None:
                respond(request_id, error="Unknown method: %s" % method)
            return
        # Kernel startup and completion can block, so they run beside the reader;
        # interrupt must stay responsive while a cell executes.
        def run():
            try:
                result = handler(params)
                if request_id is not None:
                    respond(request_id, result)
            except Exception as error:  # noqa: BLE001 - reported to the client
                traceback.print_exc(file=sys.stderr)
                if request_id is not None:
                    respond(request_id, error=str(error) or error.__class__.__name__)
        thread = threading.Thread(target=run, daemon=True)
        self.handlers.append(thread)
        thread.start()


def main():
    bridge = Bridge()
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except ValueError:
            continue
        bridge.handle(message)
    # stdin closed: let in-flight requests answer before the kernel goes away.
    for thread in bridge.handlers:
        thread.join(timeout=60)
    bridge.shutdown(None)


if __name__ == "__main__":
    main()
