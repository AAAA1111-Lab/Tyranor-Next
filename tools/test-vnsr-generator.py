#!/usr/bin/env python3
"""Check that reproducibility verification fails closed when provenance is edited."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


def main():
    """Mutate isolated copies; never alter the working tree's pinned records."""
    root = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix="vnsr-generator-") as temp:
        target = Path(temp)
        for folder in ("engine/src/main/assets/vnsr", "engine/patches/vnsr/sources"):
            shutil.copytree(root/folder,target/folder)
        (target/"tools").mkdir()
        shutil.copyfile(root/"tools/generate-vnsr-filters.py",target/"tools/generate-vnsr-filters.py")
        manifest_path = target/"engine/patches/vnsr-filters.json"
        original = (root/"engine/patches/vnsr-filters.json").read_text()
        for kind in ("valid", "source path", "license id", "license hash", "generated hash", "retained gate",
                     "repository", "revision", "version", "asset directory"):
            data = json.loads(original)
            source = data["sources"][0]
            if kind == "source path": del source["files"][0]["localPath"]
            if kind == "license id": source["license"]["spdx"] = "GPL-3.0-only"
            if kind == "license hash": source["license"]["sha256"] = "0"*64
            if kind == "generated hash": data["generated"][1]["sha256"] = "0"*64
            if kind == "retained gate": del source["verification"]
            if kind == "repository": source["repository"] = "https://github.com/AAAA1111-Lab/vnsr-mirror"
            if kind == "revision": source["revision"] = "0"*40
            if kind == "version": source["version"] = "0.2.0"
            if kind == "asset directory": data["assetDirectory"] = "vnsr-rewritten"
            manifest_path.write_text(json.dumps(data),encoding="utf-8")
            result = subprocess.run([sys.executable,str(target/"tools/generate-vnsr-filters.py"),"--check"],capture_output=True,text=True)
            assert (result.returncode == 0) == (kind == "valid"), (kind,result.stdout,result.stderr)
            print("PASS: "+kind)


if __name__ == "__main__":
    main()
