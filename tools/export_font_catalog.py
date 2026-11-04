#!/usr/bin/env python3
"""Export the existing Dart catalogue without changing release URLs, sizes or preference IDs."""
import json
import re
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "lib/font_catalog.dart").read_text()
labels = dict(re.findall(r"(\w+)\('([^']+)',", (ROOT / "lib/app_typography.dart").read_text()))
constants = {}
for match in re.finditer(r"const (\w+) = (?:'([^']+)'|(\d+));", source):
    constants[match[1]] = match[2] if match[2] is not None else int(match[3])
items = []
for block in re.findall(r"DownloadableFont\((.*?)\n  \),", source.split("const downloadableFonts = [", 1)[1], re.S):
    identity = re.search(r"typeface: AppTypeface\.(\w+)", block)[1]
    def field(name, default=None):
        value = re.search(r"\b" + name + r":\s*('([^']*)'|\d+|\w+)", block)
        if value is None: return default
        raw = value[1]
        return value[2] if value[2] is not None else int(raw) if raw.isdigit() else constants.get(raw, raw)
    urls = re.search(r"sourceUrls: \[(.*?)\]", block, re.S)[1]
    items.append({"id": identity, "name": labels[identity], "fileName": field("fileName"), "bytes": field("bytes"), "transferBytes": field("transferBytes", field("bytes")), "archiveEntry": field("archiveEntry"), "description": field("description"), "urls": re.findall(r"'([^']+)'", urls)})
assert len(items) == 13
(ROOT / "android/app/src/compose/assets/font-catalog.json").write_text(json.dumps(items, ensure_ascii=False, indent=2) + "\n")

# Preserve the upstream notices without bundling the downloadable font binaries.
import shutil
notices = ROOT / "android/app/src/compose/assets/font-licenses"
notices.mkdir(exist_ok=True)
for license_file in (ROOT / "assets/fonts").glob("OFL-*.txt"):
    shutil.copy2(license_file, notices / license_file.name)
for name in ("LICENSE", "NOTICE"):
    shutil.copy2(ROOT / name, ROOT / "android/app/src/compose/assets" / (name + ".txt"))
