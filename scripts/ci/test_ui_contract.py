"""UI resource contracts: navigation, localization, contrast, and private entry points."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"


def document(path):
    return ET.parse(ROOT / path).getroot()


def strings(module, folder, name):
    root = document(f"{module}/src/main/res/{folder}/{name}.xml")
    return {node.attrib["name"]: "".join(node.itertext()) for node in root.findall("string")}


def luminance(value):
    channels = [int(value[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    linear = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in channels]
    return sum(v * weight for v, weight in zip(linear, (0.2126, 0.7152, 0.0722)))


class UiContractTest(unittest.TestCase):
    def test_home_owns_five_independent_page_layouts(self):
        menu = document("app/src/main/res/menu/main_navigation.xml")
        self.assertEqual(["nav_home", "nav_activity", "nav_protect", "nav_family", "nav_settings"],
                         [item.attrib[ANDROID + "id"].split("/")[-1] for item in menu])
        main = document("app/src/main/res/layout/activity_main.xml")
        includes = main.findall(".//include")
        self.assertEqual(5, len(includes))
        self.assertEqual(5, len({item.attrib["layout"] for item in includes}))
        for item in includes:
            layout = item.attrib["layout"].split("/")[-1]
            page = document(f"app/src/main/res/layout/{layout}.xml")
            self.assertEqual("match_parent", page.attrib[ANDROID + "layout_height"])
        home = document("app/src/main/res/layout/page_home.xml")
        self.assertFalse(home.findall(".//androidx.recyclerview.widget.RecyclerView"))
        self.assertTrue(document("app/src/main/res/layout/page_activity.xml")
                        .findall(".//androidx.recyclerview.widget.RecyclerView"))

    def test_new_copy_has_bengali_keys_and_placeholder_parity(self):
        files = {"app": ("navigation", "detail_navigation", "rules_navigation"),
                 "parent": ("parent_pages",)}
        for module, names in files.items():
            for name in names:
                english, bengali = (strings(module, folder, name) for folder in ("values", "values-bn"))
                self.assertEqual(english.keys(), bengali.keys(), f"{module}:{name}")
                for key in english:
                    self.assertEqual(re.findall(r"%\d*\$?[sdf]", english[key]),
                                     re.findall(r"%\d*\$?[sdf]", bengali[key]), f"{module}:{key}")

    def test_shared_day_and_night_colors_have_readable_text_contrast(self):
        for folder in ("values", "values-night"):
            root = document(f"auth/src/main/res/{folder}/design.xml")
            colors = {node.attrib["name"]: node.text for node in root.findall("color")}
            pairs = (("tw_text", "tw_background"), ("tw_muted", "tw_surface"),
                     ("tw_primary", "tw_surface"), ("tw_on_primary", "tw_primary"),
                     ("tw_success", "tw_surface"), ("tw_danger", "tw_surface"),
                     ("tw_on_hero", "tw_hero"), ("tw_hero_muted", "tw_hero"))
            for foreground, background in pairs:
                light, dark = sorted((luminance(colors[foreground]), luminance(colors[background])), reverse=True)
                self.assertGreaterEqual((light + 0.05) / (dark + 0.05), 4.5,
                                        f"{folder}: {foreground} on {background}")

    def test_only_the_app_launchers_are_exported_ui(self):
        for module in ("app", "parent", "auth"):
            root = document(f"{module}/src/main/AndroidManifest.xml")
            for activity in root.findall(".//activity"):
                if activity.attrib.get(ANDROID + "exported") == "true":
                    actions = activity.findall("intent-filter/action")
                    self.assertIn("android.intent.action.MAIN",
                                  [action.attrib.get(ANDROID + "name") for action in actions])

    def test_parent_and_child_share_the_visual_theme(self):
        for module, name in (("app", "Theme.Tracewall"), ("parent", "Theme.TracewallParent")):
            root = document(f"{module}/src/main/res/values/themes.xml")
            style = next(node for node in root.findall("style") if node.attrib["name"] == name)
            self.assertEqual("Theme.Tracewall.Base", style.attrib["parent"])


if __name__ == "__main__":
    unittest.main()
