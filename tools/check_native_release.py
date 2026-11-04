#!/usr/bin/env python3
"""Fail closed until the complete local-feature parity contract has test evidence."""
import argparse
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def inspect(root: Path, report_directory: Path) -> list[str]:
    contract = json.loads((root / "docs/native-parity.json").read_text())
    features = contract.get("features", [])
    errors = []
    if not features:
        return ["功能清单为空"]
    ids = [item["id"] for item in features]
    if len(set(ids)) != len(ids):
        errors.append("功能编号重复")
    passed = set()
    xml_files = list(report_directory.rglob("*.xml"))
    if not xml_files:
        errors.append("缺少设备验收报告")
    for file in xml_files:
        tree = ET.parse(file)
        for case in tree.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None or case.find("skipped") is not None:
                errors.append("验收未通过：" + case.get("name", file.name))
            else:
                passed.add(case.get("classname", "") + "." + case.get("name", "").split("[")[0])
    for item in features:
        if item.get("status") != "verified":
            errors.append(item["id"] + "：尚未完成验收")
        evidence = item.get("tests", [])
        if not evidence or any(name not in passed for name in evidence):
            errors.append(item["id"] + "：缺少通过的对应设备测试")
        if not item.get("upstream") or any(not (root / source).is_file() for source in item["upstream"]):
            errors.append(item["id"] + "：上游行为依据文件缺失")
    build = (root / "android/app/build.gradle.kts").read_text()
    version = re.search(r'versionName\s*=\s*"([^"]+)"', build)
    if version is None or not re.fullmatch(r"\d+\.\d+\.\d+", version[1]):
        errors.append("版本仍为开发版，不能发布正式Release")
    return errors

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reports", type=Path, default=ROOT / "android/app/build/outputs/androidTest-results/connected")
    args = parser.parse_args()
    errors = inspect(ROOT, args.reports)
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT).strip():
        errors.append("工作目录尚未提交，验收证据必须对应发布提交")
    if errors:
        print("正式Release尚不可发布：\n" + "\n".join("- " + error for error in errors))
        return 1
    print("本地功能清单全部验收通过。还需构建并验证正式签名APK后发布。")
    return 0

if __name__ == "__main__":
    sys.exit(main())
