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

    def test_google_login_is_not_exposed_or_directly_declared(self):
        layout = ET.parse(ROOT / "auth/src/main/res/layout/activity_tracewall_auth.xml").getroot()
        ids = {node.get(ANDROID + "id") for node in layout.iter()}
        self.assertNotIn("@+id/auth_google", ids)

        gradle = (ROOT / "auth/build.gradle.kts").read_text(encoding="utf-8")
        for dependency in ("androidx.credentials", "identity.googleid"):
            self.assertNotIn(dependency, gradle)

        model = (ROOT / "auth/src/main/java/tracewall/auth/AuthViewModel.java").read_text(encoding="utf-8")
        for symbol in ("GetSignInWithGoogleOption", "GoogleIdTokenCredential", "GoogleAuthProvider"):
            self.assertNotIn(symbol, model)

    def test_phone_auth_is_not_exposed_or_implemented(self):
        layout = ET.parse(ROOT / "auth/src/main/res/layout/activity_tracewall_auth.xml").getroot()
        ids = {node.get(ANDROID + "id", "") for node in layout.iter()}
        for fragment in ("auth_phone", "auth_code", "auth_sms", "auth_cooldown", "auth_resend"):
            with self.subTest(layout_id=fragment):
                self.assertFalse([value for value in ids if fragment in value])

        resources = ET.parse(ROOT / "auth/src/main/res/values/strings.xml").getroot()
        names = {node.attrib["name"] for node in resources if node.tag in ("string", "plurals")}
        for fragment in ("auth_phone", "auth_code", "auth_sms", "auth_cooldown", "auth_resend"):
            with self.subTest(resource_name=fragment):
                self.assertFalse([name for name in names if fragment in name])

        for path in sorted((ROOT / "auth/src/main/java/tracewall/auth").glob("*.java")):
            with self.subTest(path=path.name):
                source = path.read_text(encoding="utf-8")
                self.assertNotRegex(source, r"(?i)\b(phone|sms|otp)\b")
                for symbol in ("PhoneAuthProvider", "PhoneAuthOptions", "PhoneAuthCredential",
                               "PhoneFlowGuard", "AccountPolicy.PHONE", "validPhone", "validCode"):
                    self.assertNotIn(symbol, source)

        self.assertFalse((ROOT / "auth/src/main/java/tracewall/auth/PhoneFlowGuard.java").exists())
        self.assertFalse((ROOT / "auth/src/test/java/tracewall/auth/PhoneFlowGuardTest.java").exists())

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
