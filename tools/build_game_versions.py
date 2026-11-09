"""Read Build Explorer Minecraft targets from VersionChecker.SUPPORTED_VERSIONS."""
import json
import re
import sys
from pathlib import Path


def game_versions(source: str) -> list[str]:
    # Proxy artifacts do not declare backend Minecraft compatibility.
    if not source:
        return []
    text = Path(source).read_text(encoding="utf-8")
    text = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.DOTALL)
    declaration = re.search(r"\bval\s+SUPPORTED_VERSIONS\b[^=]*=\s*setOf\s*\(", text)
    if declaration is None:
        raise ValueError(f"SUPPORTED_VERSIONS setOf declaration missing in {source}")
    start = declaration.end()
    depth = 1
    end = start
    while end < len(text) and depth:
        depth += (text[end] == "(") - (text[end] == ")")
        end += 1
    if depth:
        raise ValueError(f"Unclosed SUPPORTED_VERSIONS declaration in {source}")
    body = text[start:end - 1]
    pattern = r"SemanticVersion\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)"
    matches = re.findall(pattern, body)
    if not matches or re.sub(pattern, "", body).replace(",", "").strip():
        raise ValueError(f"Empty or unsupported SUPPORTED_VERSIONS entries in {source}")
    versions = []
    for parts in matches:
        major, minor, patch = map(int, parts)
        version = f"{major}.{minor}" + (f".{patch}" if patch else "")
        if version not in versions:
            versions.append(version)
    return versions


if __name__ == "__main__":
    try:
        print(json.dumps(game_versions(sys.argv[1]), separators=(",", ":")))
    except (IndexError, OSError, ValueError) as error:
        sys.exit(str(error))
