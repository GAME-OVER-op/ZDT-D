#!/usr/bin/env python3
"""Package independently installable ZDT-D tool components from module prebuilt binaries."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import stat
import tempfile
import zipfile
from pathlib import Path

COMPONENTS: dict[str, tuple[str, tuple[str, ...]]] = {
    "nfqws": ("nfqws.zip", ("nfqws",)),
    "nfqws2": ("nfqws2.zip", ("nfqws2",)),
    "byedpi": ("byedpi.zip", ("byedpi",)),
    "dpitunnel": ("dpitunnel.zip", ("dpitunnel-cli",)),
    "operaproxy": ("opera_proxy.zip", ("opera-proxy",)),
    "sing-box": ("sing_box.zip", ("sing-box",)),
    "hysteria2": ("hysteria2.zip", ("hysteria2",)),
    "wireproxy": ("wireproxy.zip", ("wireproxy",)),
    "openvpn": ("openvpn.zip", ("openvpn",)),
    "amneziawg": ("amneziawg.zip", ("amneziawg-go", "awg")),
    "mihomo": ("mihomo.zip", ("mihomo",)),
    "mieru": ("mieru.zip", ("mieru",)),
    "tor": ("tor.zip", ("torproxy", "lyrebird")),
}

ARCHES = {
    "arm64-v8a": "arm64-v8a",
    "armeabi-v7a": "arm-v7a",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_prop(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        raw = raw.strip()
        if not raw or raw.startswith("#") or "=" not in raw:
            continue
        key, value = raw.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def add_file(zf: zipfile.ZipFile, source: Path, arcname: str, executable: bool = False) -> None:
    info = zipfile.ZipInfo.from_file(source, arcname)
    if executable:
        info.external_attr = (stat.S_IFREG | 0o755) << 16
    info.compress_type = zipfile.ZIP_DEFLATED
    with source.open("rb") as handle:
        zf.writestr(info, handle.read())


def package_component(module_root: Path, output_dir: Path, component: str, asset: str, binaries: tuple[str, ...], version: str, build: str) -> Path:
    files: dict[str, dict[str, object]] = {}
    sources: list[tuple[Path, str]] = []
    for public_arch, module_arch in ARCHES.items():
        for binary in binaries:
            source = module_root / "prebuilt" / "bin" / module_arch / binary
            if not source.is_file() or source.stat().st_size <= 0:
                raise SystemExit(f"required component binary missing: {source}")
            rel = f"{public_arch}/{binary}"
            files[rel] = {"sha256": sha256(source), "size": source.stat().st_size}
            sources.append((source, rel))

    manifest = {
        "schema": 1,
        "component": component,
        "version": version,
        "build": build,
        "files": files,
    }

    target = output_dir / asset
    tmp = target.with_suffix(target.suffix + ".tmp")
    tmp.unlink(missing_ok=True)
    with zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")
        manifest_info = zipfile.ZipInfo("manifest.json")
        manifest_info.external_attr = (stat.S_IFREG | 0o644) << 16
        manifest_info.compress_type = zipfile.ZIP_DEFLATED
        zf.writestr(manifest_info, manifest_bytes)
        for source, rel in sources:
            add_file(zf, source, rel, executable=True)
    tmp.replace(target)

    with zipfile.ZipFile(target) as zf:
        names = set(zf.namelist())
        expected = {"manifest.json"} | {rel for _, rel in sources}
        if names != expected:
            raise SystemExit(f"unexpected entries in {target}: expected={sorted(expected)} actual={sorted(names)}")
        parsed = json.loads(zf.read("manifest.json"))
        if parsed.get("component") != component:
            raise SystemExit(f"manifest component mismatch in {target}")
    return target


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--module-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    module_root = args.module_root.resolve()
    output_dir = args.output.resolve()
    props = read_prop(module_root / "module.prop")
    version = props.get("version", "").strip()
    build = props.get("buildNumber", "").strip() or os.environ.get("GITHUB_RUN_NUMBER", "")
    if not version:
        raise SystemExit("module version is missing")
    if not build:
        raise SystemExit("module build number is missing")

    if output_dir.exists():
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    for component, (asset, binaries) in COMPONENTS.items():
        target = package_component(module_root, output_dir, component, asset, binaries, version, build)
        print(f"[ZDT-D] component asset: {component} -> {target} ({target.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
