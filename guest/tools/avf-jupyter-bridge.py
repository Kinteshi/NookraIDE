#!/usr/bin/env -S uv run --quiet --script
# /// script
# requires-python = ">=3.9"
# dependencies = ["jupyter_client>=8"]
# ///
"""Jupyter kernel bridge for the AVF Studio native IDE.

Speaks newline-delimited JSON-RPC 2.0 on stdin/stdout (one bridge per notebook) and drives a
single kernel through jupyter_client. Kernels come from the user's own environments (conda,
venvs, system Python with ipykernel), so nothing is installed into them.

Requests:  kernelspecs, start {name, cwd, plotly_display_ids}, execute {code, cell_id}, complete {code, cursor_pos},
           interrupt, restart, shutdown
Notifications to the client: output {cell_id, output, display_id}, clear_output {cell_id, wait},
           update_display {display_id, output}, status {state}
"""

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


class Bridge:
    def __init__(self):
        self.specs = None
        self.kernel = None
        self.client = None
        self.work = queue.Queue()
        self.handlers = []
        threading.Thread(target=self._run_work, daemon=True).start()

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
        spec = self.specs.get_kernel_spec(params["name"])
        self.plotly_display_ids = bool(params.get("plotly_display_ids")) and spec.language == "python"
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
        msg_id = self.client.execute(params["code"], allow_stdin=False, stop_on_error=True)
        notify("status", {"state": "busy"})
        while True:
            message = self.client.get_iopub_msg(timeout=None)
            if message.get("parent_header", {}).get("msg_id") != msg_id:
                continue
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
        reply = self.client.get_shell_msg(timeout=30)
        while reply.get("parent_header", {}).get("msg_id") != msg_id:
            reply = self.client.get_shell_msg(timeout=30)
        notify("status", {"state": "idle"})
        content = reply["content"]
        respond(request_id, {"status": content.get("status"), "execution_count": content.get("execution_count")})

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
        while True:
            request_id, params = self.work.get()
            try:
                self.execute(params, request_id)
            except Exception as error:  # noqa: BLE001 - reported to the client
                respond(request_id, error=str(error) or error.__class__.__name__)

    def handle(self, message):
        method = message.get("method")
        request_id = message.get("id")
        params = message.get("params") or {}
        if method == "execute":
            self.work.put((request_id, params))
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
