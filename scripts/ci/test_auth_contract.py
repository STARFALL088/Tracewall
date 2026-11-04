"""Source-level authentication integration contracts; no credentials or SDK network calls."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"


class AuthIntegrationContractTest(unittest.TestCase):
    def test_shared_account_activity_is_private(self):
        manifest = ET.parse(ROOT / "auth/src/main/AndroidManifest.xml").getroot()
        matches = [node for node in manifest.findall(".//activity")
                   if node.get(ANDROID + "name", "").endswith("AuthActivity")]
        self.assertEqual(1, len(matches))
        self.assertEqual("false", matches[0].get(ANDROID + "exported"))
        self.assertFalse(matches[0].findall("intent-filter"))

    def test_background_components_cannot_create_guest_identities(self):
        paths = [
            "app/src/main/java/tracewall/child/TracewallApp.java",
            "app/src/main/java/tracewall/child/boot/BootReceiver.java",
            "app/src/main/java/tracewall/child/sync/FirebaseSync.java",
            "parent/src/main/java/tracewall/parent/TracewallParentApp.java",
            "parent/src/main/java/tracewall/parent/sync/FirebaseParent.java",
        ]
        for path in paths:
            with self.subTest(path=path):
                source = (ROOT / path).read_text(encoding="utf-8")
                self.assertNotRegex(source, r"\bsignInAnonymously\s*\(")
                self.assertNotRegex(source, r"\bsignInWith(?:Credential|EmailAndPassword)\s*\(")

    def test_child_account_entry_keeps_its_pin_boundary(self):
        source = (ROOT / "app/src/main/java/tracewall/child/MainActivity.java").read_text(encoding="utf-8")
        self.assertRegex(source, r"accountButton\.setOnClickListener\(v -> PinGate\.require\(this,")
        self.assertIn("!PinGate.isConfigured(this)", source)
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        self.assertFalse([node for node in manifest.findall(".//activity")
                          if node.get(ANDROID + "name", "").endswith("AuthActivity")
                          and node.get(ANDROID + "exported") == "true"])

    def test_authentication_does_not_request_sms_read_permissions(self):
        forbidden = {"android.permission.READ_SMS", "android.permission.RECEIVE_SMS",
                     "android.permission.READ_PHONE_NUMBERS", "android.permission.READ_CONTACTS"}
        for module in ("auth", "app", "parent"):
            manifest = ET.parse(ROOT / module / "src/main/AndroidManifest.xml").getroot()
            names = {node.get(ANDROID + "name") for node in manifest.findall("uses-permission")}
            with self.subTest(module=module):
                self.assertFalse(names & forbidden)

    def test_shared_auth_strings_and_plurals_have_bengali_parity(self):
        documents = []
        for locale in ("values", "values-bn"):
            document = ET.parse(ROOT / f"auth/src/main/res/{locale}/strings.xml").getroot()
            documents.append({(node.tag, node.attrib["name"]): node for node in document
                              if node.tag in ("string", "plurals")})
        self.assertEqual(documents[0].keys(), documents[1].keys())
        for key, english in documents[0].items():
            bengali = documents[1][key]
            if key[0] == "plurals":
                variants = [{node.attrib["quantity"]: "".join(node.itertext()) for node in group}
                            for group in (english, bengali)]
                self.assertEqual(variants[0].keys(), variants[1].keys(), key)
                for quantity in variants[0]:
                    self.assertEqual(re.findall(r"%\d*\$?[sdf]", variants[0][quantity]),
                                     re.findall(r"%\d*\$?[sdf]", variants[1][quantity]), (key, quantity))
            else:
                self.assertEqual(re.findall(r"%\d*\$?[sdf]", "".join(english.itertext())),
                                 re.findall(r"%\d*\$?[sdf]", "".join(bengali.itertext())), key)

    def test_consumer_account_resources_have_bengali_and_placeholder_parity(self):
        for module in ("app", "parent"):
            documents = []
            for locale in ("values", "values-bn"):
                document = ET.parse(ROOT / module / f"src/main/res/{locale}/account_entry.xml").getroot()
                documents.append({node.attrib["name"]: "".join(node.itertext()) for node in document.findall("string")})
            self.assertEqual(documents[0].keys(), documents[1].keys(), module)
            for key in documents[0]:
                self.assertEqual(re.findall(r"%\d*\$?[sdf]", documents[0][key]),
                                 re.findall(r"%\d*\$?[sdf]", documents[1][key]), f"{module}:{key}")


if __name__ == "__main__":
    unittest.main()
