"""Resolve Build Explorer channel from the Gradle artifact version."""
import re
import sys


def build_channel(version: str) -> str:
    # Qualifiers are terminal: build numbers belong to separate CI metadata.
    match = re.fullmatch(r"\d+\.\d+\.\d+(?:-R\d+\.\d+)?(?:-(SNAPSHOT|DEV|WIP))?", version, re.IGNORECASE)
    if match is None:
        raise ValueError(f"Unsupported artifact version: {version!r}")
    return (match.group(1) or "release").lower()


if __name__ == "__main__":
    try:
        print(build_channel(sys.argv[1]))
    except (IndexError, ValueError) as error:
        sys.exit(str(error))
