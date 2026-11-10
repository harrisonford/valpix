#!/usr/bin/env python3
"""Turns the Swift core's test vectors into Kotlin, so both cores are held
to the same bytes.

    python3 android/scripts/gen-vectors.py
"""
import re
from pathlib import Path

root = Path(__file__).resolve().parents[2]
src = (root / "ios/ValpixCore/Tests/ValpixCoreTests/Vectors.swift").read_text()
out = [
    "// Converted by android/scripts/gen-vectors.py from the Swift core's Vectors.swift,",
    "// which program/scripts/gen-vectors.ts writes. Do not edit by hand.",
    "package com.franky.valpix.core",
    "",
    "object Vectors {",
]
in_pairs = False
for line in src.splitlines():
    s = line.strip()
    if s.startswith("//") and not s.startswith("///"):
        continue
    if s.startswith("enum Vectors") or s == "}":
        continue
    if s.startswith("static let base58Pairs"):
        out.append("    val base58Pairs: List<Pair<String, String>> = listOf(")
        in_pairs = True
        continue
    if in_pairs:
        if s == "]":
            out.append("    )")
            in_pairs = False
        else:
            a, b = re.match(r'\("([^"]*)", "([^"]*)"\),', s).groups()
            out.append(f'        "{a}" to "{b}",')
        continue
    m = re.match(r'static let (\w+) = \(address: ("[^"]*"), bump: UInt8\((\d+)\)\)', s)
    if m:
        out.append(f"    val {m[1]} = Pair({m[2]}, {m[3]})")
        continue
    m = re.match(r"static let (\w+) = \[(.*)\]$", s)
    if m:
        out.append(f"    val {m[1]} = listOf({m[2]})")
        continue
    m = re.match(r"static let (\w+): UInt64 = (\d+)", s)
    if m:
        out.append(f"    val {m[1]}: ULong = {m[2]}uL")
        continue
    m = re.match(r"static let (\w+): Int64 = (-?\d+)", s)
    if m:
        out.append(f"    val {m[1]}: Long = {m[2]}L")
        continue
    m = re.match(r"static let (\w+) = (.*)", s)
    if m:
        out.append(f"    val {m[1]} = {m[2]}")
        continue
    out.append(("    " + s) if s else "")
out.append("}")
(root / "android/core/src/test/kotlin/com/franky/valpix/core/Vectors.kt").write_text("\n".join(out) + "\n")
