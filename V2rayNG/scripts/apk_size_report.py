#!/usr/bin/env python3
"""Summarize APK size contributors from a built APK."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import zipfile


@dataclass(frozen=True)
class Bucket:
    name: str
    prefixes: tuple[str, ...]


BUCKETS = (
    Bucket("lib", ("lib/",)),
    Bucket("dex", ("classes",)),
    Bucket("assets", ("assets/",)),
    Bucket("res", ("res/", "resources.arsc")),
)


def classify(filename: str) -> str:
    for bucket in BUCKETS:
        if filename == "resources.arsc" and bucket.name == "res":
            return bucket.name
        if any(filename.startswith(prefix) for prefix in bucket.prefixes):
            if bucket.name == "dex" and not filename.endswith(".dex"):
                continue
            return bucket.name
    return "other"


def format_mb(size: int) -> str:
    return f"{size / 1024 / 1024:6.2f} MB"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="Path to the APK to inspect")
    parser.add_argument("--top", type=int, default=20, help="How many largest entries to show")
    args = parser.parse_args()

    if not args.apk.is_file():
        raise SystemExit(f"APK not found: {args.apk}")

    with zipfile.ZipFile(args.apk) as apk:
        entries = list(apk.infolist())

    total_uncompressed = sum(entry.file_size for entry in entries)
    total_compressed = sum(entry.compress_size for entry in entries)

    category_totals: dict[str, list[int]] = {}
    for entry in entries:
        bucket = classify(entry.filename)
        values = category_totals.setdefault(bucket, [0, 0])
        values[0] += entry.file_size
        values[1] += entry.compress_size

    print(f"APK: {args.apk}")
    print(f"Entries: {len(entries)}")
    print(f"Total uncompressed: {format_mb(total_uncompressed)}")
    print(f"Total compressed:   {format_mb(total_compressed)}")
    print()
    print("Category breakdown:")
    for bucket in ("lib", "assets", "dex", "res", "other"):
        uncompressed, compressed = category_totals.get(bucket, [0, 0])
        print(
            f"  {bucket:>6}: uncompressed={format_mb(uncompressed)}"
            f"  compressed={format_mb(compressed)}"
        )

    print()
    print(f"Top {args.top} entries by uncompressed size:")
    largest_entries = sorted(entries, key=lambda entry: entry.file_size, reverse=True)[: args.top]
    for entry in largest_entries:
        ratio = 0.0 if entry.file_size == 0 else (entry.compress_size / entry.file_size)
        print(
            f"  {format_mb(entry.file_size)}  compressed={format_mb(entry.compress_size)}"
            f"  ratio={ratio:>5.2%}  {entry.filename}"
        )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
