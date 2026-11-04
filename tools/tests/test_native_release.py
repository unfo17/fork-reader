import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("check_native_release", Path(__file__).resolve().parents[1] / "check_native_release.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class NativeReleaseGateTest(unittest.TestCase):
    def test_requires_all_features_and_matching_passing_tests(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "docs").mkdir()
            (root / "android/app").mkdir(parents=True)
            (root / "source.dart").write_text("upstream behavior")
            (root / "android/app/build.gradle.kts").write_text('versionName = "1.0.0"')
            reports = root / "reports"
            reports.mkdir()
            (reports / "results.xml").write_text('<testsuite><testcase classname="reader.Test" name="feature"/></testsuite>')
            contract = {"features": [{"id": "one", "status": "verified", "tests": ["reader.Test.feature"], "upstream": ["source.dart"]}]}
            manifest = root / "docs/native-parity.json"
            manifest.write_text(json.dumps(contract))
            self.assertEqual([], module.inspect(root, reports))
            contract["features"][0]["status"] = "partial"
            manifest.write_text(json.dumps(contract))
            self.assertTrue(any("尚未完成" in error for error in module.inspect(root, reports)))
            contract["features"][0]["status"] = "verified"
            manifest.write_text(json.dumps(contract))
            (reports / "results.xml").write_text('<testsuite><testcase classname="reader.Test" name="feature"><failure/></testcase></testsuite>')
            errors = module.inspect(root, reports)
            self.assertTrue(any("验收未通过" in error for error in errors))
            self.assertTrue(any("对应设备测试" in error for error in errors))

    def test_rejects_empty_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "docs").mkdir()
            (root / "docs/native-parity.json").write_text('{"features": []}')
            self.assertEqual(["功能清单为空"], module.inspect(root, root / "reports"))

if __name__ == "__main__":
    unittest.main()
