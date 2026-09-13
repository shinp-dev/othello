"""Check APK baseline hashes and optional equality with a content checkout (offline)."""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--content-root", type=Path)
    args = parser.parse_args()
    baseline = ROOT / "app/src/main/assets/opponents"
    index = json.loads((baseline / "index.json").read_text())
    for descriptor in index["packs"]:
        name = f'{descriptor["id"]}-v{descriptor["version"]}.zip'
        archive = baseline / name
        raw = archive.read_bytes()
        assert len(raw) == descriptor["sizeBytes"], name
        assert hashlib.sha256(raw).hexdigest() == descriptor["sha256"], name
        with zipfile.ZipFile(archive) as zip_file:
            manifest = json.loads(zip_file.read("manifest.json"))
            assert manifest["id"] == descriptor["id"] and manifest["version"] == descriptor["version"]
        if args.content_root:
            assert raw == (args.content_root / "opponents/dist" / name).read_bytes(), "Baseline/content differ"
    print(f"Verified {len(index['packs'])} opponent baseline archives")


if __name__ == "__main__":
    main()
