#!/usr/bin/env python3
"""Create inert Firebase build resources from the checked-in safe template.

These demo-project files are for compilation/lint only: they are not credentials
and do not configure an Android app to use the Firebase emulators. Existing
files are never read or overwritten.
"""

import argparse
from copy import deepcopy
import json
from pathlib import Path


PROJECT_ID = "demo-tracewall"
PROJECT_NUMBER = "123456789012"
# Deliberately fabricated; never substitute a real API key in this script.
API_KEY = "AIzaSy" + "0" * 33
APPLICATIONS = {
    "app": ("tracewall.child", "0123456789abcdef0123456789abcdef"),
    "parent": ("tracewall.parent", "fedcba9876543210fedcba9876543210"),
}
TEMPLATE = Path(__file__).resolve().parents[2] / "app/google-services.json.example"


def generate(output_root: Path) -> list[Path]:
    targets = [output_root / module / "google-services.json" for module in APPLICATIONS]
    # Check both targets first; do not leave one generated file behind if the
    # other module already has its own config. No existing config is opened.
    for target in targets:
        if target.exists() or target.is_symlink():
            raise FileExistsError(f"Refusing to overwrite existing config: {target}")

    template = json.loads(TEMPLATE.read_text(encoding="utf-8"))
    for target, (package_name, app_hash) in zip(targets, APPLICATIONS.values()):
        config = deepcopy(template)
        config.pop("_comment", None)
        config["project_info"] = {
            "project_number": PROJECT_NUMBER,
            "firebase_url": f"https://{PROJECT_ID}-default-rtdb.firebaseio.com",
            "project_id": PROJECT_ID,
            "storage_bucket": f"{PROJECT_ID}.appspot.com",
        }
        client = config["client"][0]
        client["client_info"]["mobilesdk_app_id"] = (
            f"1:{PROJECT_NUMBER}:android:{app_hash}"
        )
        client["client_info"]["android_client_info"]["package_name"] = package_name
        client["api_key"] = [{"current_key": API_KEY}]
        target.parent.mkdir(parents=True, exist_ok=True)
        # Exclusive creation also prevents overwriting a config created after
        # the preflight check above.
        with target.open("x", encoding="utf-8") as stream:
            json.dump(config, stream, indent=2)
            stream.write("\n")
    return targets


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-root", type=Path, required=True)
    args = parser.parse_args()
    try:
        for target in generate(args.output_root):
            print(f"Created synthetic {PROJECT_ID} build config: {target}")
    except FileExistsError as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
