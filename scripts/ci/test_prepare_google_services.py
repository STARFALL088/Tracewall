#!/usr/bin/env python3
"""Test only temporary synthetic configs; never inspect local app configs."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "prepare_google_services", HERE / "prepare-google-services.py"
)
assert SPEC is not None and SPEC.loader is not None
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)


class PrepareGoogleServicesTest(unittest.TestCase):
    def setUp(self):
        # Keep local probes inside this worktree, even if TMPDIR is unset.
        scratch = HERE.parents[1] / "build/ci-config-tests"
        scratch.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def test_both_packages_use_only_synthetic_demo_project_values(self):
        targets = PREPARE.generate(self.root)
        self.assertEqual(len(targets), 2)
        app_ids = set()
        for module, (package, _) in PREPARE.APPLICATIONS.items():
            # These are newly generated fixtures, not local Firebase configs.
            text = (self.root / module / "google-services.json").read_text()
            config = json.loads(text)
            self.assertNotIn("YOUR_", text)
            self.assertNotIn("_comment", config)
            self.assertEqual(config["configuration_version"], "1")
            self.assertEqual(config["project_info"]["project_id"], "demo-tracewall")
            self.assertEqual(config["project_info"]["project_number"], "123456789012")
            self.assertEqual(
                config["project_info"]["firebase_url"],
                "https://demo-tracewall-default-rtdb.firebaseio.com",
            )
            self.assertEqual(len(config["client"]), 1)
            client = config["client"][0]
            self.assertEqual(client["client_info"]["android_client_info"]["package_name"], package)
            self.assertEqual(client["api_key"], [{"current_key": "AIzaSy" + "0" * 33}])
            self.assertEqual(client["oauth_client"], [])
            app_ids.add(client["client_info"]["mobilesdk_app_id"])
        self.assertEqual(len(app_ids), 2)

    def test_existing_config_is_not_read_or_overwritten(self):
        sentinel = self.root / "parent/google-services.json"
        sentinel.parent.mkdir()
        sentinel.write_text("synthetic sentinel, deliberately not JSON")
        with self.assertRaisesRegex(FileExistsError, "Refusing to overwrite"):
            PREPARE.generate(self.root)
        self.assertEqual(sentinel.read_text(), "synthetic sentinel, deliberately not JSON")
        self.assertFalse((self.root / "app/google-services.json").exists())

    def test_dangling_symlink_is_not_followed(self):
        target = self.root / "app/google-services.json"
        target.parent.mkdir()
        target.symlink_to(self.root / "absent.json")
        with self.assertRaises(FileExistsError):
            PREPARE.generate(self.root)
        self.assertFalse((self.root / "absent.json").exists())
        self.assertFalse((self.root / "parent/google-services.json").exists())


if __name__ == "__main__":
    unittest.main()
