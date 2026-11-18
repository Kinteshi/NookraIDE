#!/usr/bin/env python3
"""
Automated on-device accessibility & golden-screenshot test runner (G9 / G10).

Starts a temporary TLS quirecode-daemon with a deterministic fixture workspace,
forwards it over `adb reverse`, installs/launches `io.github.kinteshi.quirecode.dev`,
walks workbench scenarios while auditing TalkBack labels and UI bounds, and either
records (`--update-goldens`) or verifies (`--verify`) reference screenshots against
`apps/native-android-ide/app/src/test/resources/goldens/workbench-goldens.json`.
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

from PIL import Image

PACKAGE = "io.github.kinteshi.quirecode.dev"
ACTIVITY = "io.github.kinteshi.quirecode.MainActivity"
REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
GOLDENS_PATH = REPO_ROOT / "apps/native-android-ide/app/src/test/resources/goldens/workbench-goldens.json"
DEFAULT_APK = REPO_ROOT / "apps/native-android-ide/app/build/outputs/apk/debug/app-debug.apk"
DEFAULT_DAEMON = REPO_ROOT / "target/debug/quirecode-daemon"

GRID_COLS = 16
GRID_ROWS = 10
MEAN_CHANNEL_TOLERANCE = 12.0
MAX_CELL_TOLERANCE = 42
BOUND_TOLERANCE = 0.06
DAEMON_PORT = 19876


def adb(serial: str, *args: str, check: bool = True, text: bool = True) -> subprocess.CompletedProcess:
    cmd = ["adb", "-s", serial, *args]
    return subprocess.run(cmd, check=check, capture_output=True, text=text)


def resize_app_task(
    serial: str,
    left: int,
    top: int,
    right: int,
    bottom: int,
    *,
    check: bool = True,
) -> None:
    pattern = rf"Task\{{[^}}]*#(\d+)[^}}]*A=\d+:{re.escape(PACKAGE)}"
    task_id: str | None = None
    for _ in range(5):
        out = adb(serial, "shell", "dumpsys", "activity", "activities", check=False).stdout
        m = re.search(pattern, out)
        if m:
            task_id = m.group(1)
            break
        time.sleep(0.3)
    if task_id is None:
        if check:
            raise RuntimeError(f"Could not find active Task id for {PACKAGE} in dumpsys activity activities")
        return
    adb(
        serial,
        "shell",
        "am",
        "task",
        "resize",
        task_id,
        str(left),
        str(top),
        str(right),
        str(bottom),
        check=check,
    )
    time.sleep(1.2)


def dump_ui(serial: str) -> ET.Element:
    for _ in range(3):
        raw = adb(serial, "exec-out", "uiautomator", "dump", "/dev/tty", check=False).stdout
        if "<hierarchy" in raw and ">" in raw:
            xml_text = raw[raw.index("<hierarchy") : raw.rindex(">") + 1]
            return ET.fromstring(xml_text)
        time.sleep(0.5)
    raise RuntimeError("Failed to obtain valid uiautomator XML dump")


def parse_bounds(raw: str) -> tuple[int, int, int, int]:
    m = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", raw or "")
    if not m:
        return (0, 0, 0, 0)
    return int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4))


def content_bounds(root: ET.Element) -> tuple[int, int, int, int]:
    for node in root.iter("node"):
        if node.attrib.get("resource-id") == "android:id/content":
            b = parse_bounds(node.attrib.get("bounds", ""))
            if b[2] > b[0] and b[3] > b[1]:
                return b
    for node in root.iter("node"):
        b = parse_bounds(node.attrib.get("bounds", ""))
        if b[2] > b[0] and b[3] > b[1]:
            return b
    return (0, 0, 1, 1)


def node_labels(node: ET.Element) -> list[str]:
    out = []
    for attr in ("content-desc", "text"):
        val = (node.attrib.get(attr) or "").strip()
        if val:
            out.append(val)
    return out


def all_labels(root: ET.Element) -> list[str]:
    labels: list[str] = []
    for node in root.iter("node"):
        labels.extend(node_labels(node))
    return labels


def subtree_has_label(node: ET.Element) -> bool:
    return any(node_labels(n) for n in node.iter("node"))


def find_node(
    root: ET.Element,
    *,
    text: str | None = None,
    desc: str | None = None,
    contains: str | None = None,
    cls: str | None = None,
) -> ET.Element | None:
    for node in root.iter("node"):
        if cls and node.attrib.get("class") != cls:
            continue
        if text is not None and (node.attrib.get("text") or "") != text:
            continue
        if desc is not None and (node.attrib.get("content-desc") or "") != desc:
            continue
        if contains is not None:
            combined = f"{node.attrib.get('content-desc', '')}\n{node.attrib.get('text', '')}"
            if contains not in combined:
                continue
        b = parse_bounds(node.attrib.get("bounds", ""))
        if b[2] > b[0] and b[3] > b[1]:
            return node
    return None


def find_row_open_button(root: ET.Element, folder_name: str) -> ET.Element | None:
    btn = find_node(root, desc=f"Open {folder_name}")
    if btn is not None:
        return btn
    return find_node(root, text=f"Open current ({folder_name})")


def tap_bounds(serial: str, bounds: tuple[int, int, int, int]) -> None:
    cx = (bounds[0] + bounds[2]) // 2
    cy = (bounds[1] + bounds[3]) // 2
    adb(serial, "shell", "input", "tap", str(cx), str(cy))


def tap_node(serial: str, **query: str) -> ET.Element:
    root = dump_ui(serial)
    node = find_node(root, **query)
    if node is None:
        raise AssertionError(f"Could not find UI node matching {query}; available={all_labels(root)[:30]}")
    tap_bounds(serial, parse_bounds(node.attrib.get("bounds", "")))
    return node


def wait_for_label(serial: str, substring: str, timeout_s: float = 12.0) -> ET.Element:
    deadline = time.time() + timeout_s
    last_labels: list[str] = []
    while time.time() < deadline:
        root = dump_ui(serial)
        last_labels = all_labels(root)
        if any(substring in lbl for lbl in last_labels):
            return root
        time.sleep(0.5)
    raise AssertionError(f"Timed out waiting for label containing {substring!r}; saw={last_labels[:35]}")


def input_text(serial: str, text: str) -> None:
    escaped = "".join(f"\\{ch}" if ch in r' \?&|;$()"<>![]{}*' else ch for ch in text)
    adb(serial, "shell", "input", "text", escaped)


def audit_clickable_accessibility(root: ET.Element, c_bounds: tuple[int, int, int, int]) -> list[str]:
    """Find clickable interactive controls inside the app content whose subtrees lack any text/content-desc."""
    cx0, cy0, cx1, cy1 = c_bounds
    cw = max(1, cx1 - cx0)
    ch = max(1, cy1 - cy0)
    unlabeled: list[str] = []
    for node in root.iter("node"):
        if node.attrib.get("clickable") != "true":
            continue
        cls = node.attrib.get("class", "")
        if "EditText" in cls:
            continue
        b = parse_bounds(node.attrib.get("bounds", ""))
        w = b[2] - b[0]
        h = b[3] - b[1]
        if w <= 0 or h <= 0:
            continue
        # Ignore full-window modal overlay scrims or root View wrappers
        if w >= cw * 0.9 and h >= ch * 0.85:
            continue
        if not subtree_has_label(node):
            parent_map = {child: p for p in root.iter("node") for child in p}
            parent = parent_map.get(node)
            parent_xml = ET.tostring(parent, encoding="unicode") if parent is not None else ET.tostring(node, encoding="unicode")
            unlabeled.append(f"{cls.split('.')[-1]}@{b} parent_xml={parent_xml[:600]}")
    return unlabeled


def compute_rgb_grid(png_bytes: bytes, crop: tuple[int, int, int, int], cols: int = GRID_COLS, rows: int = GRID_ROWS) -> tuple[int, int, list[list[int]]]:
    import io

    with Image.open(io.BytesIO(png_bytes)) as im:
        rgb = im.convert("RGB")
        full_w, full_h = rgb.size
        x0 = max(0, min(crop[0], full_w - 1))
        y0 = max(0, min(crop[1], full_h - 1))
        x1 = max(x0 + 1, min(crop[2], full_w))
        y1 = max(y0 + 1, min(crop[3], full_h))
        cropped = rgb.crop((x0, y0, x1, y1))
        small = cropped.resize((cols, rows), Image.Resampling.BOX)
        grid: list[list[int]] = []
        for y in range(rows):
            for x in range(cols):
                r, g, b = small.getpixel((x, y))
                grid.append([int(r), int(g), int(b)])
        return full_w, full_h, grid


def extract_structural_bounds(root: ET.Element, c_bounds: tuple[int, int, int, int], keys: list[str]) -> dict[str, list[float]]:
    cx0, cy0, cx1, cy1 = c_bounds
    cw = max(1, cx1 - cx0)
    ch = max(1, cy1 - cy0)
    out: dict[str, list[float]] = {}
    for key in keys:
        node = find_node(root, contains=key)
        if node is None:
            continue
        b = parse_bounds(node.attrib.get("bounds", ""))
        out[key] = [
            round((b[0] - cx0) / cw, 4),
            round((b[1] - cy0) / ch, 4),
            round((b[2] - cx0) / cw, 4),
            round((b[3] - cy0) / ch, 4),
        ]
    return out


def capture_scenario(
    serial: str,
    name: str,
    required_labels: list[str],
    structural_keys: list[str],
    shot_dir: pathlib.Path | None = None,
) -> dict:
    time.sleep(0.8)
    root = dump_ui(serial)
    c_bounds = content_bounds(root)
    labels = all_labels(root)
    png = adb(serial, "exec-out", "screencap", "-p", text=False).stdout
    if shot_dir is not None:
        shot_dir.mkdir(parents=True, exist_ok=True)
        (shot_dir / f"{name}.png").write_bytes(png)
    for req in required_labels:
        if not any(req in lbl for lbl in labels):
            raise AssertionError(f"[{name}] missing required TalkBack/text label {req!r}; saw={labels[:40]}")
    unlabeled = audit_clickable_accessibility(root, c_bounds)
    if unlabeled:
        raise AssertionError(f"[{name}] found unlabelled clickable nodes: {unlabeled}\nlabels={labels[:50]}")
    w, h, grid = compute_rgb_grid(png, c_bounds)
    structural = extract_structural_bounds(root, c_bounds, structural_keys)
    missing_struct = [k for k in structural_keys if k not in structural]
    if missing_struct:
        raise AssertionError(f"[{name}] missing structural bounds for {missing_struct}")
    return {
        "width": w,
        "height": h,
        "contentBounds": list(c_bounds),
        "requiredLabels": required_labels,
        "structuralBounds": structural,
        "rgbGrid": grid,
    }


def verify_against_golden(name: str, actual: dict, expected: dict, meta: dict) -> None:
    exp_grid = expected["rgbGrid"]
    act_grid = actual["rgbGrid"]
    if len(exp_grid) != len(act_grid):
        raise AssertionError(f"[{name}] grid size mismatch: {len(act_grid)} vs {len(exp_grid)}")
    total_delta = 0
    max_delta = 0
    for (er, eg, eb), (ar, ag, ab) in zip(exp_grid, act_grid):
        for d in (abs(er - ar), abs(eg - ag), abs(eb - ab)):
            total_delta += d
            if d > max_delta:
                max_delta = d
    mean_delta = total_delta / (len(exp_grid) * 3)
    mean_tol = float(meta.get("meanChannelTolerance", MEAN_CHANNEL_TOLERANCE))
    cell_tol = int(meta.get("maxCellTolerance", MAX_CELL_TOLERANCE))
    if mean_delta > mean_tol or max_delta > cell_tol:
        raise AssertionError(
            f"[{name}] screenshot drift: mean_delta={mean_delta:.2f} (limit {mean_tol}), "
            f"max_cell_delta={max_delta} (limit {cell_tol})"
        )
    bound_tol = float(meta.get("boundTolerance", BOUND_TOLERANCE))
    for key, exp_box in expected["structuralBounds"].items():
        act_box = actual["structuralBounds"].get(key)
        if act_box is None:
            raise AssertionError(f"[{name}] missing structural bound {key!r}")
        for idx, (e_val, a_val) in enumerate(zip(exp_box, act_box)):
            if abs(e_val - a_val) > bound_tol:
                raise AssertionError(
                    f"[{name}] structural bound {key!r}[{idx}] drifted: {a_val} vs {e_val} (tol {bound_tol})"
                )
    print(f"[PASS] {name}: labels OK, 0 unlabeled clickables, mean_delta={mean_delta:.2f}, max_delta={max_delta}")


def build_fixture_workspace(home: pathlib.Path) -> pathlib.Path:
    ws = home / "Projects/fixture-ws"
    (ws / "src").mkdir(parents=True, exist_ok=True)
    (ws / "notebooks").mkdir(parents=True, exist_ok=True)
    (ws / "src/main.rs").write_text('fn main() {\n    let value = 42;\n    println!("hello {}", value);\n}\n')
    (ws / "README.md").write_text("# Fixture Workspace\n\nAutomated accessibility and golden test project.\n")
    nb = {
        "nbformat": 4,
        "nbformat_minor": 5,
        "metadata": {"language_info": {"name": "python"}},
        "cells": [
            {
                "id": "cell-code-1",
                "cell_type": "code",
                "execution_count": 1,
                "metadata": {},
                "source": ["x = 6 * 7\n", "print(f\"answer={x}\")\n"],
                "outputs": [
                    {
                        "output_type": "stream",
                        "name": "stdout",
                        "text": ["answer=42\n"],
                    }
                ],
            },
            {
                "id": "cell-md-2",
                "cell_type": "markdown",
                "metadata": {},
                "source": ["# Summary\n", "\n", "Offline notebook accessibility test."],
            },
        ],
    }
    (ws / "notebooks/demo.ipynb").write_text(json.dumps(nb, indent=2))
    return ws


def pair_and_connect(serial: str, pair_uri: str) -> None:
    tap_node(serial, text="Set up")
    wait_for_label(serial, "A computer on your network")
    tap_node(serial, text="A computer on your network")
    wait_for_label(serial, "Pair and connect")
    tap_node(serial, cls="android.widget.EditText")
    time.sleep(0.3)
    input_text(serial, pair_uri)
    time.sleep(0.4)
    tap_node(serial, text="Pair and connect")
    wait_for_label(serial, "fixture-ws", timeout_s=15.0)


def open_quick_open_and_pick(serial: str, query: str, expected_result: str) -> None:
    tap_node(serial, contains="Search files by name, Quick Open")
    wait_for_label(serial, "Search files by name")
    input_text(serial, query)
    deadline = time.time() + 8.0
    while time.time() < deadline:
        root = dump_ui(serial)
        row = find_node(root, cls="android.widget.TextView", text=expected_result)
        if row is not None:
            tap_bounds(serial, parse_bounds(row.attrib.get("bounds", "")))
            return
        time.sleep(0.4)
    raise AssertionError(f"Could not find Quick Open result {expected_result!r}")


def dismiss_banner_if_present(serial: str) -> None:
    for _ in range(3):
        root = dump_ui(serial)
        node = find_node(root, text="Not now")
        if node is None:
            return
        tap_bounds(serial, parse_bounds(node.attrib.get("bounds", "")))
        time.sleep(0.5)


def wait_for_editor_dismissing_prompts(serial: str, label: str, timeout_s: float = 14.0) -> ET.Element:
    deadline = time.time() + timeout_s
    last_labels: list[str] = []
    while time.time() < deadline:
        root = dump_ui(serial)
        last_labels = all_labels(root)
        prompt_btn = find_node(root, text="Not now")
        if prompt_btn is not None:
            tap_bounds(serial, parse_bounds(prompt_btn.attrib.get("bounds", "")))
            time.sleep(0.5)
            continue
        if any(label in lbl for lbl in last_labels):
            return root
        time.sleep(0.5)
    raise AssertionError(f"Timed out waiting for {label!r}; saw={last_labels[:35]}")


def run_scenarios(serial: str, daemon_bin: pathlib.Path, shot_dir: pathlib.Path | None) -> tuple[str, dict[str, dict]]:
    model = adb(serial, "shell", "getprop", "ro.product.model").stdout.strip() or "android"
    results: dict[str, dict] = {}
    daemon_proc: subprocess.Popen | None = None

    with tempfile.TemporaryDirectory(prefix="quirecode-e2e-") as tmp:
        home = pathlib.Path(tmp)
        build_fixture_workspace(home)
        cfg = home / ".config/avf-studio"
        cfg.mkdir(parents=True, exist_ok=True)
        token_path = cfg / "token"
        token_path.write_text(secrets.token_hex(32))
        os.chmod(token_path, 0o600)

        env = os.environ.copy()
        env.update(
            {
                "HOME": str(home),
                "AVF_TLS": "1",
                "AVF_BIND_ADDR": f"127.0.0.1:{DAEMON_PORT}",
                "AVF_WORKSPACE_ROOTS": str(home / "Projects"),
                "RUST_LOG": "warn",
            }
        )
        pair_out = subprocess.check_output(
            [str(daemon_bin), "pair", "--remote", f"127.0.0.1:{DAEMON_PORT}"],
            env=env,
            text=True,
        )
        m = re.search(r"avf://\S+", pair_out)
        if not m:
            raise RuntimeError(f"Failed to parse pairing URI from:\n{pair_out}")
        pair_uri = m.group(0)

        daemon_proc = subprocess.Popen([str(daemon_bin)], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            time.sleep(0.8)
            if daemon_proc.poll() is not None:
                raise RuntimeError(f"Daemon exited prematurely: {daemon_proc.stdout.read().decode()}")

            adb(serial, "reverse", f"tcp:{DAEMON_PORT}", f"tcp:{DAEMON_PORT}")
            adb(serial, "shell", f"pm clear {PACKAGE}")
            adb(serial, "shell", f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS", check=False)
            adb(serial, "shell", f"am start -W -n {PACKAGE}/{ACTIVITY}")
            resize_app_task(serial, 0, 60, 2960, 1736)
            wait_for_label(serial, "Open workspace", timeout_s=15.0)

            # 1. Welcome screen (expanded tablet layout)
            results["welcome_expanded"] = capture_scenario(
                serial,
                "welcome_expanded",
                required_labels=[
                    "Toggle side bar",
                    "Open workspace",
                    "Search files by name, Quick Open",
                    "Problems: 0 errors, 0 warnings",
                ],
                structural_keys=[
                    "Toggle side bar",
                    "Open workspace",
                    "Search files by name, Quick Open",
                    "Problems: 0 errors, 0 warnings",
                ],
                shot_dir=shot_dir,
            )

            # 2. Pair & open workspace + file in editor
            pair_and_connect(serial, pair_uri)
            time.sleep(0.5)
            root = dump_ui(serial)
            open_btn = find_row_open_button(root, "fixture-ws")
            if open_btn is None:
                open_btn = find_node(root, text="fixture-ws")
                if open_btn is not None:
                    tap_bounds(serial, parse_bounds(open_btn.attrib.get("bounds", "")))
                    time.sleep(0.5)
                    root = dump_ui(serial)
                    open_btn = find_row_open_button(root, "fixture-ws")
            if open_btn is None:
                raise AssertionError(f"Could not open fixture-ws; labels={all_labels(root)[:30]}")
            tap_bounds(serial, parse_bounds(open_btn.attrib.get("bounds", "")))
            wait_for_label(serial, "Folder src, collapsed", timeout_s=12.0)
            tap_node(serial, contains="Folder src, collapsed")
            wait_for_label(serial, "File main.rs")
            tap_node(serial, contains="File main.rs")
            wait_for_editor_dismissing_prompts(serial, "Code editor, main.rs")

            results["workspace_editor_expanded"] = capture_scenario(
                serial,
                "workspace_editor_expanded",
                required_labels=[
                    "Workspace: fixture-ws, switch workspace",
                    "Folder src, expanded",
                    "File main.rs, active",
                    "main.rs, active tab",
                    "Close main.rs",
                    "Breadcrumb folder: src",
                    "Current file: main.rs",
                    "Code editor, main.rs",
                ],
                structural_keys=[
                    "Workspace: fixture-ws, switch workspace",
                    "main.rs, active tab",
                    "Code editor, main.rs",
                ],
                shot_dir=shot_dir,
            )

            # 3. Command Palette
            tap_node(serial, contains="Search files by name, Quick Open")
            wait_for_label(serial, "Search files by name")
            input_text(serial, ">Accept")
            wait_for_label(serial, "Inline Suggest: Accept Next Word")
            results["command_palette_expanded"] = capture_scenario(
                serial,
                "command_palette_expanded",
                required_labels=[
                    "Show and Run Commands",
                    "Inline Suggest: Accept Next Word",
                    "Ctrl+Right",
                ],
                structural_keys=[
                    "Inline Suggest: Accept Next Word",
                ],
                shot_dir=shot_dir,
            )
            adb(serial, "shell", "input", "keyevent", "KEYCODE_ESCAPE")
            time.sleep(0.4)

            # 4. Notebook cells & outputs
            open_quick_open_and_pick(serial, "demo.ipynb", "demo.ipynb")
            wait_for_editor_dismissing_prompts(serial, "Code cell 1 of 2")
            results["notebook_expanded"] = capture_scenario(
                serial,
                "notebook_expanded",
                required_labels=[
                    "demo.ipynb, in notebooks, active",
                    "demo.ipynb, active tab",
                    "Code cell 1 of 2",
                    "Cell output 1 of 1, stdout stream",
                    "Markdown cell 2 of 2",
                ],
                structural_keys=[
                    "demo.ipynb, active tab",
                    "Code cell 1 of 2",
                    "Cell output 1 of 1, stdout stream",
                ],
                shot_dir=shot_dir,
            )

            # 5. Compact layout (temporary freeform window dimensions, always restored in finally)
            resize_app_task(serial, 0, 60, 960, 1736)
            wait_for_label(serial, "Files", timeout_s=8.0)
            results["compact_phone_layout"] = capture_scenario(
                serial,
                "compact_phone_layout",
                required_labels=[
                    "Workspace: fixture-ws, switch workspace",
                    "Files",
                    "Editor",
                    "Terminal",
                    "Chat",
                    "More",
                ],
                structural_keys=[
                    "Workspace: fixture-ws, switch workspace",
                    "Files",
                    "Editor",
                ],
                shot_dir=shot_dir,
            )
        finally:
            resize_app_task(serial, 0, 60, 2960, 1736, check=False)
            if daemon_proc is not None and daemon_proc.poll() is None:
                daemon_proc.terminate()
                try:
                    daemon_proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    daemon_proc.kill()
            adb(serial, "reverse", "--remove-all", check=False)
            adb(serial, "shell", f"pm clear {PACKAGE}", check=False)
            adb(serial, "shell", f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS", check=False)

    return model, results


def main() -> int:
    parser = argparse.ArgumentParser(description="Run on-device QuireCode accessibility and golden screenshot suite")
    parser.add_argument("--serial", default=os.environ.get("AVF_DEVICE_SERIAL", ""), help="ADB device serial (e.g. 192.0.2.66:40433)")
    parser.add_argument("--apk", type=pathlib.Path, default=DEFAULT_APK)
    parser.add_argument("--daemon", type=pathlib.Path, default=DEFAULT_DAEMON)
    parser.add_argument("--install", action="store_true", help="Install debug APK before running")
    parser.add_argument("--update-goldens", action="store_true", help="Write reference goldens JSON instead of verifying")
    parser.add_argument("--verify", action="store_true", help="Verify device run against reference goldens JSON")
    parser.add_argument("--screenshots-dir", type=pathlib.Path, default=None, help="Optional dir to keep raw screenshots")
    args = parser.parse_args()

    if not args.update_goldens and not args.verify:
        args.verify = True
    if not args.serial:
        print("Pass --serial <adb-serial> or set AVF_DEVICE_SERIAL", file=sys.stderr)
        return 2
    if not args.daemon.is_file():
        subprocess.run(["cargo", "build", "-p", "avf-daemon"], cwd=REPO_ROOT, check=True)
    if args.install:
        adb(args.serial, "install", "-r", str(args.apk))

    model, actual = run_scenarios(args.serial, args.daemon, args.screenshots_dir)
    if args.update_goldens:
        GOLDENS_PATH.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "schemaVersion": 1,
            "deviceModel": model,
            "gridCols": GRID_COLS,
            "gridRows": GRID_ROWS,
            "meanChannelTolerance": MEAN_CHANNEL_TOLERANCE,
            "maxCellTolerance": MAX_CELL_TOLERANCE,
            "boundTolerance": BOUND_TOLERANCE,
            "scenarios": actual,
        }
        GOLDENS_PATH.write_text(json.dumps(payload, indent=2) + "\n")
        print(f"Updated golden file: {GOLDENS_PATH} ({len(actual)} scenarios on {model})")
        return 0

    if not GOLDENS_PATH.is_file():
        raise FileNotFoundError(f"Missing golden file {GOLDENS_PATH}; run with --update-goldens first")
    golden = json.loads(GOLDENS_PATH.read_text())
    scenarios = golden["scenarios"]
    for name, expected in scenarios.items():
        if name not in actual:
            raise AssertionError(f"Scenario {name!r} did not run")
        verify_against_golden(name, actual[name], expected, golden)
    print(f"All {len(scenarios)} on-device accessibility & golden checks passed on {model}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
