#!/usr/bin/env python3
"""Writes the app's third-party notices: every component the release APK ships, with its licence text.

Components, in three groups:
  * Android libraries: the release build's runtime classpath (`./gradlew :app:thirdPartyArtifacts`), with each
    library's licence from its POM (or its parent's) and its own licence file when the artifact carries one.
  * The guest daemon: the Rust crates `avf-daemon` is built from for the tablet (aarch64-unknown-linux-gnu), with
    the licence files in each crate's source (`cargo metadata`; crates must be fetched, as a build does).
  * Files bundled with the app: VS Code's Python grammar and themes, the Seti icons, and the tm4e module.

Where a component offers a choice of licences (MIT OR Apache-2.0), the first of MIT, Apache-2.0, BSD, ISC is used.
Licences without a file of their own use the standard text in scripts/licenses/ (from the SPDX licence list).

Output: apps/native-android-ide/app/src/main/assets/licenses/notices.json, read by the app's licences screen.
Run it after changing dependencies; CI checks the committed file is current.

    python3 scripts/third-party-notices.py
"""
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "apps", "native-android-ide")
OUT = os.path.join(APP, "app", "src", "main", "assets", "licenses", "notices.json")
STANDARD = os.path.join(ROOT, "scripts", "licenses")
GRADLE_CACHE = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")
PREFERRED = ["MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "Zlib", "Unlicense", "BSL-1.0"]

# Licence names as POMs write them.
POM_NAMES = [
    (r"apache", "Apache-2.0"),
    (r"\bmit\b", "MIT"),
    (r"eclipse public license.*2", "EPL-2.0"),
    (r"bsd.*3|new bsd|revised bsd|3-clause", "BSD-3-Clause"),
    (r"bsd", "BSD-2-Clause"),
    (r"\bisc\b", "ISC"),
    (r"cc0|public domain", "CC0-1.0"),
]

texts = {}


def keep(text):
    """Stores a licence text once; returns its key."""
    text = text.replace("\r\n", "\n").strip() + "\n"
    key = hashlib.sha256(text.encode()).hexdigest()[:16]
    texts[key] = text
    return key


def standard(spdx, name=None, url=None):
    """The standard text; for a component without a licence file of its own, its template's copyright line names
    the component's authors and where to find them, rather than a holder this script can't know."""
    path = os.path.join(STANDARD, spdx + ".txt")
    if not os.path.exists(path):
        sys.exit(f"No standard text for {spdx}: add scripts/licenses/{spdx}.txt")
    with open(path, encoding="utf-8") as f:
        text = f.read()
    if name is not None:
        holder = f"the authors of {name}" + (f" ({url})" if url else "")
        text = re.sub(r"Copyright \(c\) <year> <copyright holders>", f"Copyright (c) {holder}", text)
        text = re.sub(r"Copyright \(c\) <year> <owner>\.?( All rights reserved\.)?", f"Copyright (c) {holder}.", text)
        text = re.sub(r"Copyright \(c\) <year>,? <copyright holder>", f"Copyright (c) {holder}", text)
    return text


def choose(expression):
    """The licences used from an SPDX expression: one from each OR choice, all of an AND."""
    expression = expression.replace("/", " OR ")
    parts = []
    for conjunct in re.split(r"\s+AND\s+", expression.replace("(", "").replace(")", "")):
        options = [o.strip() for o in re.split(r"\s+OR\s+", conjunct) if o.strip()]
        options = [o.replace(" WITH LLVM-exception", "") for o in options]
        ranked = sorted(options, key=lambda o: PREFERRED.index(o) if o in PREFERRED else len(PREFERRED))
        parts.append(ranked[0])
    return list(dict.fromkeys(parts))


# Android libraries ---------------------------------------------------------------------------

def pom_path(group, name, version):
    found = glob.glob(os.path.join(GRADLE_CACHE, group, name, version, "*", f"{name}-{version}.pom"))
    return found[0] if found else None


def pom_info(group, name, version, depth=0):
    """(licence names, project url, display name) from the POM, its parents filling what it lacks."""
    path = pom_path(group, name, version)
    if path is None:
        return [], None, None
    root = ET.parse(path).getroot()
    ns = {"m": root.tag.split("}")[0].strip("{")} if root.tag.startswith("{") else {"m": ""}
    q = (lambda tag: f"m:{tag}") if ns["m"] else (lambda tag: tag)
    licenses = [l.findtext(q("name"), default="", namespaces=ns) for l in root.findall(f"{q('licenses')}/{q('license')}", ns)]
    url = root.findtext(q("url"), namespaces=ns) or root.findtext(f"{q('scm')}/{q('url')}", namespaces=ns)
    title = root.findtext(q("name"), namespaces=ns)
    parent = root.find(q("parent"), ns)
    if (not licenses or not url) and parent is not None and depth < 4:
        parent_group = parent.findtext(q("groupId"), namespaces=ns)
        pl, pu, _ = pom_info(parent_group, parent.findtext(q("artifactId"), namespaces=ns),
                             parent.findtext(q("version"), namespaces=ns), depth + 1)
        licenses = licenses or pl
        # A parent's link only from the same project: a generic parent (Sonatype's) links to its own site.
        if parent_group == group or parent_group.startswith(group.rsplit(".", 1)[0] + "."):
            url = url or pu
    return licenses, url, title


def spdx_of_pom(names):
    found = []
    for name in names:
        for pattern, spdx in POM_NAMES:
            if re.search(pattern, name, re.I):
                found.append(spdx)
                break
        else:
            sys.exit(f"Unknown licence name in a POM: {name!r}; add it to POM_NAMES")
    return list(dict.fromkeys(found))


def embedded_file(group, name, version, pattern):
    """A file inside the artifact (META-INF/LICENSE…, META-INF/NOTICE…), the shortest path matching [pattern]."""
    for path in glob.glob(os.path.join(GRADLE_CACHE, group, name, version, "*", f"{name}-{version}.*")):
        if not path.endswith((".jar", ".aar")):
            continue
        with zipfile.ZipFile(path) as archive:
            names = [n for n in archive.namelist() if re.search(r"(^|/)" + pattern + r"(\.txt|\.md)?$", n, re.I)]
            if names:
                return archive.read(sorted(names, key=len)[0]).decode("utf-8", "replace")
    return None


def embedded_license(group, name, version):
    """The artifact's own licence file, which carries the copyright line."""
    return embedded_file(group, name, version, r"(LICENSE|LICENCE|COPYING)")


def android_components():
    listing = subprocess.run(["./gradlew", "-q", ":app:thirdPartyArtifacts"], cwd=APP, capture_output=True, text=True, check=True)
    components = []
    for line in listing.stdout.split():
        group, name, version = line.split(":")
        names, url, title = pom_info(group, name, version)
        if not names:
            sys.exit(f"No licence found for {line}")
        spdx = spdx_of_pom(names)
        text = embedded_license(group, name, version) or "\n\n".join(standard(s, f"{group}:{name}", url) for s in spdx)
        # Apache-2.0 asks for a work's NOTICE to be passed on with it.
        notice = embedded_file(group, name, version, r"NOTICE")
        if notice:
            text += "\n\nNOTICE\n\n" + notice
        components.append({
            "group": "Android libraries",
            "name": f"{group}:{name}",
            "title": title or name,
            "version": version,
            "license": " AND ".join(spdx),
            "url": url,
            "text": keep(text),
        })
    return components


# The guest daemon's crates ------------------------------------------------------------------------

LICENSE_FILES = {
    "MIT": [r"LICEN[SC]E[-_.]?MIT", r"MIT"],
    "Apache-2.0": [r"LICEN[SC]E[-_.]?APACHE", r"APACHE"],
    "Unlicense": [r"UNLICENSE"],
    "BSD-3-Clause": [r"BSD"],
    "BSD-2-Clause": [r"BSD"],
}


def crate_text(directory, spdx_list, name, url):
    files = [f for f in os.listdir(directory) if re.match(r"(LICEN[SC]E|COPYING|UNLICENSE|NOTICE)", f, re.I)]
    chosen = []
    for spdx in spdx_list:
        match = next((f for f in files for p in LICENSE_FILES.get(spdx, []) if re.search(p, f, re.I)), None)
        if match is None and len(files) == 1:
            match = files[0]
        if match is not None:
            with open(os.path.join(directory, match), encoding="utf-8", errors="replace") as f:
                chosen.append(f.read())
        else:
            chosen.append(standard(spdx, name, url))
    return "\n\n".join(chosen)


def daemon_components():
    metadata = json.loads(subprocess.run(
        ["cargo", "metadata", "--format-version", "1", "--filter-platform", "aarch64-unknown-linux-gnu"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout)
    packages = {p["id"]: p for p in metadata["packages"]}
    nodes = {n["id"]: n for n in metadata["resolve"]["nodes"]}
    workspace = set(metadata["workspace_members"])
    root = next(i for i in workspace if packages[i]["name"] == "avf-daemon")
    seen, stack = set(), [root]
    while stack:
        current = stack.pop()
        if current in seen:
            continue
        seen.add(current)
        for dependency in nodes[current]["deps"]:
            if any(kind["kind"] is None for kind in dependency["dep_kinds"]):
                stack.append(dependency["pkg"])
    components = []
    for package in sorted((packages[i] for i in seen - workspace), key=lambda p: (p["name"], p["version"])):
        if not package.get("license"):
            sys.exit(f"Crate {package['name']} has no licence expression")
        spdx = choose(package["license"])
        components.append({
            "group": "Guest daemon (Rust crates)",
            "name": package["name"],
            "title": package["name"],
            "version": package["version"],
            "license": " AND ".join(spdx),
            "url": package.get("repository") or package.get("homepage"),
            "text": keep(crate_text(os.path.dirname(package["manifest_path"]), spdx, package["name"],
                                    package.get("repository") or package.get("homepage"))),
        })
    return components


# Files bundled with the app ----------------------------------------------------------------------

# EPL-2.0 asks that those who receive the program in object form are told where its source is.
TM4E_SOURCE = (
    "The source code of this copy of tm4e, with the changes made to it, is in the app's repository, in "
    "apps/native-android-ide/tm4e (https://github.com/Kinteshi/QuireCode/tree/main/apps/native-android-ide/tm4e). "
    "Its README says where it comes from and what was changed.\n\n"
)


def bundled_components():
    assets = os.path.join(APP, "app", "src", "main", "assets")
    with open(os.path.join(assets, "textmate", "VSCODE-LICENSE.txt"), encoding="utf-8") as f:
        vscode = keep(f.read())
    with open(os.path.join(assets, "icon-themes", "SETI-NOTICES.txt"), encoding="utf-8") as f:
        seti = keep(f.read())
    return [
        {"group": "Bundled files", "name": "vscode-python-grammar", "title": "Python grammar and language configuration (VS Code)",
         "version": None, "license": "MIT", "url": "https://github.com/microsoft/vscode", "text": vscode},
        {"group": "Bundled files", "name": "vscode-themes", "title": "Dark+ and Light+ syntax colours (VS Code)",
         "version": None, "license": "MIT", "url": "https://github.com/microsoft/vscode", "text": vscode},
        {"group": "Bundled files", "name": "seti-icons", "title": "Seti file icons (VS Code's icon theme)",
         "version": None, "license": "MIT", "url": "https://github.com/jesseweed/seti-ui", "text": seti},
        {"group": "Bundled files", "name": "tm4e", "title": "Eclipse tm4e core (the app's tm4e module, from sora-editor 0.23.6's copy)",
         "version": None, "license": "EPL-2.0", "url": "https://github.com/eclipse-tm4e/tm4e", "text": keep(TM4E_SOURCE + standard("EPL-2.0"))},
    ]


def main():
    components = bundled_components() + android_components() + daemon_components()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"components": components, "texts": texts}, f, indent=1, sort_keys=True, ensure_ascii=False)
        f.write("\n")
    groups = {}
    for c in components:
        groups[c["group"]] = groups.get(c["group"], 0) + 1
    print(f"{len(components)} components, {len(texts)} distinct licence texts: {groups}")
    print(os.path.relpath(OUT, ROOT), f"{os.path.getsize(OUT) // 1024} KiB")


if __name__ == "__main__":
    main()
