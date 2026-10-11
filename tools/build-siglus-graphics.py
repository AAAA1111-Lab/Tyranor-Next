#!/usr/bin/env python3
"""Build the complete pinned Siglus library with downstream-only graphics patches."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tarfile
from pathlib import Path


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run(arguments, cwd=None, env=None):
    result = subprocess.run([str(a) for a in arguments], cwd=cwd, env=env,
                            capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(f"{arguments[0]} failed: {result.stderr or result.stdout}")
    return result.stdout.strip()


def symbols(nm, library):
    return {line.split()[-1] for line in run([nm, "--dynamic", "--defined-only", library]).splitlines()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--cmake-bin", type=Path, required=True)
    parser.add_argument("--cargo-home", type=Path, required=True)
    parser.add_argument("--rustup-home", type=Path, required=True)
    parser.add_argument("--toolchain", default="1.99.0-x86_64-pc-windows-gnu")
    parser.add_argument("--jobs", type=int, default=6)
    parser.add_argument("--baseline-only", action="store_true")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--install", action="store_true", help="Copy verified staged library into jniLibs")
    args = parser.parse_args()
    if args.jobs < 1 or (args.baseline_only and args.install):
        parser.error("jobs must be positive; baseline builds cannot be installed")
    root = Path(__file__).resolve().parents[1]
    patches = root / "engine/patches/siglus"
    manifest = json.loads((patches / "manifest.json").read_text(encoding="utf-8"))
    build = root / "build/siglus-stage-a"
    build.mkdir(parents=True, exist_ok=True)
    archive = build / f"siglus-{manifest['revision'][:7]}.tar.gz"
    if not archive.is_file():
        run(["curl.exe", "--fail", "--location", "--retry", "4", "--retry-all-errors",
             "--output", archive,
             f"https://codeload.github.com/xmoezzz/siglus_rs/tar.gz/{manifest['revision']}"])
    if sha256(archive) != manifest["archiveSha256"]:
        raise ValueError("Source archive differs from the pinned upstream; no files changed")
    source = build / "source"
    if not source.exists():
        source.mkdir()
        with tarfile.open(archive, "r:gz") as bundle:
            for member in bundle:
                relative = Path(*Path(member.name).parts[1:])
                if not relative.parts:
                    continue
                target = (source / relative).resolve()
                if source.resolve() not in target.parents or not (member.isdir() or member.isfile()):
                    raise ValueError(f"Unsafe source archive entry: {member.name}")
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with bundle.extractfile(member) as data, target.open("wb") as output:
                        shutil.copyfileobj(data, output)
        run(["git", "init"], source)
        run(["git", "config", "core.autocrlf", "false"], source)
        run(["git", "add", "--force", "--all"], source)
        run(["git", "-c", "user.name=Siglus build snapshot", "-c", "user.email=build@localhost",
             "commit", "-m", f"build: upstream archive {manifest['revision']}"], source)
    if sha256(source / "Cargo.lock") != manifest["cargoLockSha256"]:
        raise ValueError("Unexpected Cargo.lock; refusing to mix upstream dependency trees")
    # Compare Git blob hashes in one tree walk; no mutable remote HEAD is used.
    tree = subprocess.run(["git", "ls-tree", "-r", "-z", "HEAD"], cwd=source,
                          capture_output=True, check=True).stdout
    blobs = {}
    for item in tree.split(b"\0"):
        if not item:
            continue
        entry, relative = item.split(b"\t", 1)
        blobs[relative.decode("utf-8")] = entry.split()[2].decode("ascii")
    archive_files = set()
    with tarfile.open(archive, "r:gz") as bundle:
        for member in bundle:
            if not member.isfile():
                continue
            relative = Path(*Path(member.name).parts[1:]).as_posix()
            archive_files.add(relative)
            digest = hashlib.sha1(f"blob {member.size}\0".encode())
            with bundle.extractfile(member) as data:
                for block in iter(lambda: data.read(1024 * 1024), b""):
                    digest.update(block)
            if blobs.get(relative) != digest.hexdigest():
                raise ValueError(f"Build baseline does not match upstream archive: {relative}")
    if set(blobs) != archive_files:
        raise ValueError("Build baseline has unexpected files outside the upstream archive")
    if run(["git", "ls-files", "--others", "--exclude-standard"], source):
        raise ValueError("Untracked source files in build tree; inspect before rebuilding")
    patch_paths = []
    for entry in manifest["patches"]:
        patch = patches / entry["file"]
        if sha256(patch) != entry["sha256"]:
            raise ValueError(f"Patch hash mismatch: {patch}")
        patch_paths.append(patch)
    applied = subprocess.run(["git", "apply", "--reverse", "--check", *map(str, patch_paths)],
                             cwd=source, capture_output=True).returncode == 0
    if args.baseline_only:
        if applied or run(["git", "diff", "HEAD", "--name-only"], source):
            raise ValueError("Baseline build requires a clean source tree")
    elif not applied:
        if run(["git", "diff", "--name-only"], source):
            raise ValueError("Unexpected source edits before applying downstream patches")
        run(["git", "apply", "--check", *patch_paths], source)
        run(["git", "apply", *patch_paths], source)
    # Reject other edits even when reverse --check could still accept this patch.
    if not args.baseline_only:
        expected = set()
        for patch in patch_paths:
            expected.update(re.findall(r"^\+\+\+ b/(.+)$", patch.read_text(encoding="utf-8"), re.M))
        actual = set(run(["git", "diff", "--name-only"], source).splitlines())
        if actual != expected:
            raise ValueError(f"Unexpected patched source files: {actual ^ expected}")
        # Reverse into a temporary index and compare the complete baseline tree.
        temporary_index = build / "verify-index"
        if temporary_index.exists():
            temporary_index.unlink()
        index_env = os.environ.copy()
        index_env["GIT_INDEX_FILE"] = str(temporary_index)
        try:
            run(["git", "read-tree", "HEAD"], source, index_env)
            run(["git", "add", "--all"], source, index_env)
            run(["git", "apply", "--cached", "--reverse", *patch_paths], source, index_env)
            if run(["git", "write-tree"], source, index_env) != run(["git", "rev-parse", "HEAD^{tree}"], source):
                raise ValueError("Patched tree contains additional changes")
        finally:
            temporary_index.unlink(missing_ok=True)
    ndk = args.ndk.resolve()
    ndk_properties = (ndk / "source.properties").read_text(encoding="utf-8")
    if f"Pkg.Revision = {manifest['ndkVersion']}" not in ndk_properties:
        raise ValueError("NDK version differs from build manifest")
    ndk_bin = ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin"
    env = os.environ.copy()
    env.update(CARGO_HOME=str(args.cargo_home.resolve()), RUSTUP_HOME=str(args.rustup_home.resolve()))
    host_bin = build / "host-bin"
    host_bin.mkdir(exist_ok=True)
    # Rust's GNU host uses the conventional dlltool name for raw-dylib imports.
    # Reuse this pinned NDK's LLVM binary without modifying the SDK or user PATH.
    shutil.copy2(ndk_bin / "llvm-dlltool.exe", host_bin / "dlltool.exe")
    env["PATH"] = os.pathsep.join(map(str, [args.cargo_home.resolve() / "bin", host_bin,
                                             ndk_bin, args.cmake_bin.resolve()])) + os.pathsep + env["PATH"]
    rust_info = run([args.cargo_home.resolve() / "bin/rustc.exe", f"+{args.toolchain}", "-Vv"], env=env)
    if f"release: {manifest['rustVersion']}\n" not in rust_info + "\n" or f"commit-hash: {manifest['rustCommit']}" not in rust_info:
        raise ValueError("Rust version/commit differs from build manifest")
    target = manifest["target"]
    platform = f"{target}{manifest['androidApi']}"
    env["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"] = str(ndk_bin / "clang.exe")
    env.pop("CARGO_ENCODED_RUSTFLAGS", None)
    env["RUSTFLAGS"] = (f"-Clink-arg=--target={platform} -Clink-arg=-Wl,-z,max-page-size=16384 "
                        "-Clink-arg=-Wl,-z,common-page-size=16384")
    env["CC_aarch64_linux_android"] = str(ndk_bin / "clang.exe")
    env["CXX_aarch64_linux_android"] = str(ndk_bin / "clang++.exe")
    env["CFLAGS_aarch64_linux_android"] = f"--target={platform}"
    env["CXXFLAGS_aarch64_linux_android"] = f"--target={platform}"
    env["AR_aarch64_linux_android"] = str(ndk_bin / "llvm-ar.exe")
    env["LIBCLANG_PATH"] = str(ndk_bin)
    if args.prepare_only:
        print(f"Verified source, patches and toolchain: {source}")
        return
    name = "baseline" if args.baseline_only else "patched"
    log = build / f"{name}-build.log"
    target_dir = source / "target"
    command = [str(args.cargo_home.resolve() / "bin/cargo.exe"), f"+{args.toolchain}", "build",
               "--locked", "--release", "--target", target, "--target-dir", str(target_dir),
               "-p", manifest["package"], "--lib",
               "-j", str(args.jobs)]
    print(f"Building {name}; log: {log}", flush=True)
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run(command, cwd=source, env=env, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f"Cargo failed; inspect {log}")
    built = target_dir / f"{target}/release/libgame_launcher.so"
    installed = root / "engine/src/main/jniLibs/arm64-v8a/libsiglus.so"
    reference = root / manifest["previousExportsFile"]
    if sha256(reference) != manifest["previousExportsSha256"]:
        raise ValueError("Previous ABI reference hash differs from the manifest")
    previous = {line.split()[-1] for line in reference.read_text(encoding="utf-8").splitlines()}
    exports = symbols(ndk_bin / "llvm-nm.exe", built)
    # ABI exports used by both Siglus and the three software engines are shared.
    required = {symbol for symbol in previous if symbol.startswith(("siglus_", "game_"))}
    if args.baseline_only:
        required.discard(manifest["geometryAbi"])
    else:
        required.add(manifest["geometryAbi"])
    if required - exports:
        raise ValueError(f"Missing shared library ABI: {sorted(required - exports)}")
    elf = run([ndk_bin / "llvm-readelf.exe", "--program-headers", "--wide", built])
    loads = [line for line in elf.splitlines() if line.strip().startswith("LOAD ")]
    if not loads or any(int(line.split()[-1], 16) < 16384 for line in loads):
        raise ValueError("Library is not aligned for 16 KiB Android pages")
    stage = build / "staged"
    stage.mkdir(exist_ok=True)
    destination = stage / f"libsiglus-{name}.so"
    shutil.copy2(built, destination)
    report = dict(manifest, buildKind=name, librarySha256=sha256(destination),
                  bytes=destination.stat().st_size, rust=rust_info, requiredAbi=sorted(required),
                  missingPreviousExports=sorted(previous - exports), addedExports=sorted(exports - previous))
    (stage / f"{name}-build.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (stage / f"{name}-exports.txt").write_text("\n".join(sorted(exports)) + "\n", encoding="utf-8")
    (stage / f"{name}-elf.txt").write_text(elf + "\n", encoding="utf-8")
    if args.install:
        shutil.copy2(destination, installed)
    print(f"Verified {len(required)} shared ABI exports; staged: {destination}")
    print(f"SHA256: {report['librarySha256']}")


if __name__ == "__main__":
    main()