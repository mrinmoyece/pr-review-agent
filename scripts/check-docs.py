#!/usr/bin/env python3
"""Validate canonical project documentation without external dependencies."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
DOCS = [
    ROOT / "README.md",
    ROOT / "CONTRIBUTING.md",
    ROOT / "SECURITY.md",
    *sorted((ROOT / "docs").rglob("*.md")),
]
LINK = re.compile(
    r"(?<!!)\[(?:[^\[\]]|!\[[^\]]*\]\([^)]+\))*\]\(([^)]+)\)"
)
IMAGE = re.compile(r"!\[[^\]]*\]\(([^)]+)\)")
HEADING = re.compile(r"^#{1,6}\s+(.+?)\s*$")
PLACEHOLDER = re.compile(r"\b(?:TODO|TBD|FIXME|COMING SOON)\b", re.IGNORECASE)


def anchor(text: str) -> str:
    text = re.sub(r"<[^>]+>", "", text.strip().lower())
    text = re.sub(r"[^\w\- ]", "", text)
    return re.sub(r"-+", "-", text.replace(" ", "-")).strip("-")


def anchors(path: Path) -> set[str]:
    result: set[str] = set()
    counts: dict[str, int] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = HEADING.match(line)
        if not match:
            continue
        base = anchor(match.group(1))
        count = counts.get(base, 0)
        counts[base] = count + 1
        result.add(base if count == 0 else f"{base}-{count}")
    return result


def main() -> int:
    errors: list[str] = []
    for document in DOCS:
        text = document.read_text(encoding="utf-8")
        relative_document = document.relative_to(ROOT)
        for line_number, line in enumerate(text.splitlines(), start=1):
            if PLACEHOLDER.search(line):
                errors.append(
                    f"{relative_document}:{line_number}: unresolved placeholder"
                )
            for raw_target in (*LINK.findall(line), *IMAGE.findall(line)):
                target = raw_target.strip().split(maxsplit=1)[0].strip("<>")
                if target.startswith(("http://", "https://", "mailto:")):
                    continue
                path_part, separator, fragment = target.partition("#")
                linked = (
                    document
                    if not path_part
                    else (document.parent / unquote(path_part)).resolve()
                )
                if not linked.exists():
                    errors.append(
                        f"{relative_document}:{line_number}: missing link {target}"
                    )
                    continue
                if separator and linked.suffix.lower() == ".md":
                    expected = unquote(fragment).lower()
                    if expected and expected not in anchors(linked):
                        errors.append(
                            f"{relative_document}:{line_number}: missing anchor {target}"
                        )
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Validated {len(DOCS)} canonical Markdown documents.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
