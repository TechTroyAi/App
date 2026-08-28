#!/usr/bin/env python3
"""Guard against runtime-crash patterns that the Kotlin compiler only warns about.

Why this exists
---------------
Blockhold Defense v1.2 installed fine and then died on launch. The cause was not
packaging: the game logic was written against Kotlin <= 1.3 collection semantics
while the project compiles with Kotlin 2.0.21.

In Kotlin 1.7 the stdlib reintroduced `maxBy`, `minBy`, `max()` and `min()` as
NON-NULL functions that THROW `NoSuchElementException` on an empty receiver
(`maxBy` is annotated `@JvmName("maxByOrThrow")`). Code written for the old
nullable versions still compiles - the leftover `?.`, `?:` and `== null` checks
only produce warnings - and then throws the first time the collection is empty.

Twenty-two such sites shipped in v1.2. Warnings are easy to scroll past, so this
check makes them build failures instead.

Usage:
    python3 scripts/lint-kotlin-pitfalls.py            # whole source tree
    python3 scripts/lint-kotlin-pitfalls.py path.kt    # specific files

Exit code is non-zero when an ERROR-level pattern is found.
"""

from __future__ import annotations

import glob
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_GLOB = os.path.join(REPO, "app", "src", "main", "java", "**", "*.kt")

# (regex, level, message)
RULES: list[tuple[re.Pattern, str, str]] = [
    (
        re.compile(r"\.maxBy\s*\{"),
        "error",
        "maxBy throws NoSuchElementException on an empty collection since Kotlin 1.7 "
        "(@JvmName \"maxByOrThrow\"). Use maxByOrNull.",
    ),
    (
        re.compile(r"\.minBy\s*\{"),
        "error",
        "minBy throws NoSuchElementException on an empty collection since Kotlin 1.7. "
        "Use minByOrNull.",
    ),
    (
        re.compile(r"\.max\(\)"),
        "error",
        "max() throws NoSuchElementException on an empty collection since Kotlin 1.7. "
        "Use maxOrNull().",
    ),
    (
        re.compile(r"\.min\(\)"),
        "error",
        "min() throws NoSuchElementException on an empty collection since Kotlin 1.7. "
        "Use minOrNull().",
    ),
    (
        re.compile(r"\.maxWith\s*\(|\.minWith\s*\("),
        "error",
        "maxWith/minWith throw on an empty collection. Use maxWithOrNull/minWithOrNull.",
    ),
    (
        re.compile(r"\.(?:first|last|single)\s*\(\s*\)"),
        "warn",
        "first()/last()/single() throw on an empty collection - confirm the receiver can "
        "never be empty, or use the OrNull variant.",
    ),
    (
        re.compile(r"\.(?:first|last|single)\s*\{"),
        "warn",
        "first{}/last{}/single{} throw when nothing matches - confirm a match is guaranteed, "
        "or use the OrNull variant.",
    ),
    (
        re.compile(r"\.reduce\s*\{"),
        "warn",
        "reduce throws on an empty collection. Prefer fold with an initial value.",
    ),
    (
        re.compile(r"\.getValue\s*\("),
        "warn",
        "Map.getValue throws NoSuchElementException on a missing key. Ensure every enum "
        "constant is mapped, or use get()/getOrElse().",
    ),
    (
        re.compile(r"preferences\.get(?:Int|Long|Float|Boolean|String|StringSet)\s*\("),
        "warn",
        "Raw SharedPreferences read: getInt/getBoolean throw ClassCastException if an older "
        "build stored that key with a different type, which bricks upgrades. Use the "
        "prefInt/prefBoolean helpers or wrap in try/catch.",
    ),
]

# Lines that are deliberately exempt, keyed by "<file>:<line-substring>".
ALLOWLIST_SUBSTRINGS = (
    # The helpers themselves are the try/catch wrappers.
    "try { preferences.getInt(key, fallback) }",
    "try { preferences.getBoolean(key, fallback) }",
)


def strip_noise(line: str) -> str:
    """Remove string literals and line comments so they cannot trigger matches."""
    line = re.sub(r'"""(?:.|\n)*?"""', '""', line)
    line = re.sub(r'"(?:\\.|[^"\\])*"', '""', line)
    line = re.sub(r"//.*$", "", line)
    return line


def in_try_block(lines: list[str], index: int) -> bool:
    """Cheap heuristic: is this line inside a `try {` that has not closed yet?"""
    depth = 0
    for i in range(index, -1, -1):
        text = strip_noise(lines[i])
        depth += text.count("}") - text.count("{")
        if depth < 0 and re.search(r"\btry\s*\{", text):
            return True
        if depth < -6:
            return False
    return False


def check_enum_map_coverage() -> tuple[list[str], list[str]]:
    """Prove the SpriteCatalog `getValue` calls can never throw.

    Every sprite lookup is `map.getValue(enumConstant)`, so a single enum constant added
    without a matching map entry is a NoSuchElementException the first time that thing is
    drawn. Parse the enums and the maps and require exact coverage.
    """
    src_dir = os.path.join(REPO, "app", "src", "main", "java", "ai", "techtroy", "blockhold")
    try:
        catalog = open(os.path.join(src_dir, "SpriteCatalog.kt")).read()
        models = open(os.path.join(src_dir, "GameModels.kt")).read()
        view = open(os.path.join(src_dir, "GameView.kt")).read()
    except OSError:
        return [], []

    enums: dict[str, set[str]] = {}
    for match in re.finditer(r"enum class (\w+)[^{]*\{(.*?)\n\}", models + view, re.S):
        constants = set()
        for line in match.group(2).split("\n"):
            found = re.match(r"^\s*([A-Z][A-Z0-9_]*)\s*(\(|,|;|$)", line)
            if found:
                constants.add(found.group(1))
        enums[match.group(1)] = constants

    pairs = [
        ("enemies", "EnemyKind"), ("traps", "TrapKind"), ("corruptions", "CorruptionKind"),
        ("evolutions", "TowerEvolution"), ("craftedItems", "CraftedItem"),
        ("imbuements", "Imbuement"), ("utilities", "UtilityKind"),
        ("projectiles", "TowerKind"), ("impacts", "TowerKind"),
    ]
    errors, notes = [], []
    for map_name, enum_name in pairs:
        block = re.search(r"val " + map_name + r" = mapOf\((.*?)\n    \)", catalog, re.S)
        if not block:
            errors.append(f"SpriteCatalog.{map_name} not found - update this lint rule")
            continue
        mapped = set(re.findall(enum_name + r"\.(\w+)", block.group(1)))
        expected = enums.get(enum_name, set())
        if not expected:
            errors.append(f"enum {enum_name} not found - update this lint rule")
            continue
        missing = sorted(expected - mapped)
        if missing:
            errors.append(
                f"SpriteCatalog.{map_name} is missing {enum_name} constant(s) {missing} - "
                f"getValue() will throw NoSuchElementException when one is drawn"
            )
        else:
            notes.append(f"{map_name}: all {len(expected)} {enum_name} constants mapped")
    return errors, notes


def main() -> int:
    targets = sys.argv[1:] or sorted(glob.glob(DEFAULT_GLOB, recursive=True))
    if not targets:
        print("no Kotlin sources found", file=sys.stderr)
        return 1

    errors: list[str] = []
    warns: list[str] = []

    for path in targets:
        with open(path) as handle:
            lines = handle.read().split("\n")
        rel = os.path.relpath(path, REPO)
        for number, raw in enumerate(lines):
            if any(token in raw for token in ALLOWLIST_SUBSTRINGS):
                continue
            line = strip_noise(raw)
            if not line.strip() or line.strip().startswith("*"):
                continue
            for pattern, level, message in RULES:
                if not pattern.search(line):
                    continue
                # A raw preference read inside try/catch is already safe.
                if "SharedPreferences read" in message and in_try_block(lines, number):
                    continue
                entry = f"{rel}:{number + 1}: {message}\n      {raw.strip()[:120]}"
                (errors if level == "error" else warns).append(entry)

    coverage_errors, coverage_notes = check_enum_map_coverage()
    errors.extend(coverage_errors)

    for note in coverage_notes:
        print(f"  ok    SpriteCatalog {note}")
    for entry in warns:
        print(f"  warn  {entry}")
    for entry in errors:
        print(f"  ERROR {entry}")

    print()
    print(f"{len(errors)} error(s), {len(warns)} warning(s) across {len(targets)} file(s)")
    if errors:
        print("\nThese patterns compile cleanly and throw at runtime. Fix them before shipping.")
        return 1
    print("No Kotlin 1.7 collection-semantics hazards found.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
