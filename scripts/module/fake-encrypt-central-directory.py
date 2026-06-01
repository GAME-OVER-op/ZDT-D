#!/usr/bin/env python3
"""Patch ZIP central directory to look encrypted while keeping local entries plain.

This intentionally only sets the traditional ZIP encrypted bit in Central
Directory headers. Local File Headers and file data are not modified. Tools that
trust Central Directory may refuse extraction; local-header readers such as
BusyBox unzip can still extract the archive.
"""

from __future__ import annotations

import argparse
import shutil
import struct
import sys
from dataclasses import dataclass
from pathlib import Path

EOCD_SIG = b"PK\x05\x06"
CD_SIG = b"PK\x01\x02"
LFH_SIG = b"PK\x03\x04"
ENCRYPTED_FLAG = 0x0001
ZIP64_MARKER_16 = 0xFFFF
ZIP64_MARKER_32 = 0xFFFFFFFF


@dataclass(frozen=True)
class CentralEntry:
    name: str
    cd_offset: int
    local_offset: int
    cd_flags: int
    local_flags: int


def find_eocd(data: bytes) -> int:
    # EOCD can have a comment up to 65535 bytes. EOCD is 22 bytes.
    start = max(0, len(data) - 65557)
    idx = data.rfind(EOCD_SIG, start)
    if idx < 0:
        raise RuntimeError("EOCD signature not found")
    return idx


def read_eocd(data: bytes) -> tuple[int, int, int]:
    eocd = find_eocd(data)
    if eocd + 22 > len(data):
        raise RuntimeError("truncated EOCD")

    disk_no, cd_disk, _entries_disk, entries_total = struct.unpack_from("<HHHH", data, eocd + 4)
    cd_size, cd_offset = struct.unpack_from("<II", data, eocd + 12)
    if disk_no != 0 or cd_disk != 0:
        raise RuntimeError("multi-disk ZIP is not supported")
    if entries_total == ZIP64_MARKER_16 or cd_offset == ZIP64_MARKER_32 or cd_size == ZIP64_MARKER_32:
        raise RuntimeError("ZIP64 is not supported by this simple patcher")
    return entries_total, cd_offset, cd_size


def walk_central_directory(data: bytes) -> list[CentralEntry]:
    entries_total, cd_offset, cd_size = read_eocd(data)
    pos = cd_offset
    end = cd_offset + cd_size
    entries: list[CentralEntry] = []

    for _ in range(entries_total):
        if pos + 46 > len(data) or data[pos : pos + 4] != CD_SIG:
            raise RuntimeError(f"central directory signature not found at offset {pos}")

        cd_flags = struct.unpack_from("<H", data, pos + 8)[0]
        name_len, extra_len, comment_len = struct.unpack_from("<HHH", data, pos + 28)
        local_offset = struct.unpack_from("<I", data, pos + 42)[0]
        name_start = pos + 46
        name_end = name_start + name_len
        raw_name = bytes(data[name_start:name_end])
        try:
            name = raw_name.decode("utf-8")
        except UnicodeDecodeError:
            name = raw_name.decode("cp437", errors="replace")

        if local_offset + 30 > len(data) or data[local_offset : local_offset + 4] != LFH_SIG:
            raise RuntimeError(f"local header signature not found for {name!r} at offset {local_offset}")
        local_flags = struct.unpack_from("<H", data, local_offset + 6)[0]

        entries.append(
            CentralEntry(
                name=name,
                cd_offset=pos,
                local_offset=local_offset,
                cd_flags=cd_flags,
                local_flags=local_flags,
            )
        )
        pos += 46 + name_len + extra_len + comment_len

    if pos != end:
        raise RuntimeError(f"central directory ended at {pos}, expected {end}")
    return entries


def patch_zip(input_zip: Path, output_zip: Path) -> int:
    if input_zip.resolve() != output_zip.resolve():
        shutil.copyfile(input_zip, output_zip)

    data = bytearray(output_zip.read_bytes())
    entries = walk_central_directory(data)
    patched = 0
    for entry in entries:
        if entry.local_flags & ENCRYPTED_FLAG:
            raise RuntimeError(f"refusing to patch already-encrypted local entry: {entry.name}")
        flags_offset = entry.cd_offset + 8
        flags = struct.unpack_from("<H", data, flags_offset)[0]
        if not (flags & ENCRYPTED_FLAG):
            struct.pack_into("<H", data, flags_offset, flags | ENCRYPTED_FLAG)
            patched += 1
    output_zip.write_bytes(data)
    return patched


def check_zip(path: Path, require_patched: bool = True) -> list[CentralEntry]:
    data = path.read_bytes()
    entries = walk_central_directory(data)
    if not entries:
        raise RuntimeError("ZIP has no entries")
    errors: list[str] = []
    for entry in entries:
        cd_encrypted = bool(entry.cd_flags & ENCRYPTED_FLAG)
        local_encrypted = bool(entry.local_flags & ENCRYPTED_FLAG)
        if require_patched and not cd_encrypted:
            errors.append(f"central encrypted bit is not set: {entry.name}")
        if local_encrypted:
            errors.append(f"local encrypted bit is set: {entry.name}")
    if errors:
        raise RuntimeError("\n".join(errors))
    return entries


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input_zip", nargs="?", help="input ZIP")
    parser.add_argument("output_zip", nargs="?", help="output ZIP")
    parser.add_argument("--in-place", action="store_true", help="patch input ZIP in place")
    parser.add_argument("--check", action="store_true", help="validate patched central directory flags")
    parser.add_argument("--summary", action="store_true", help="print entry summary")
    args = parser.parse_args()

    try:
        if args.check:
            if not args.input_zip or args.output_zip:
                parser.error("--check expects exactly one ZIP path")
            entries = check_zip(Path(args.input_zip))
            if args.summary:
                for entry in entries:
                    print(f"{entry.name}\tcd_flags=0x{entry.cd_flags:04x}\tlocal_flags=0x{entry.local_flags:04x}")
            print(f"check ok: {len(entries)} entries")
            return 0

        if args.in_place:
            if not args.input_zip or args.output_zip:
                parser.error("--in-place expects exactly one ZIP path")
            input_zip = Path(args.input_zip)
            output_zip = input_zip
        else:
            if not args.input_zip or not args.output_zip:
                parser.error("expected <input.zip> <output.zip>")
            input_zip = Path(args.input_zip)
            output_zip = Path(args.output_zip)

        if not input_zip.is_file():
            raise RuntimeError(f"input ZIP not found: {input_zip}")
        patched = patch_zip(input_zip, output_zip)
        entries = check_zip(output_zip)
        print(f"patched entries: {patched}/{len(entries)}")
        print(f"output: {output_zip}")
        return 0
    except Exception as exc:  # noqa: BLE001 - command line tool
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
