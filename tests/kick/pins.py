"""Values the Kick tests share with the patches, read from the patch sources so each is written once.

The stock Hermes bundle's SHA-256 is HermesBundle.SOURCE_SHA256 in patches/.../kick/shared/Hermes.kt; the supported
Kick version is the single AppTarget in patches/.../kick/shared/Constants.kt. A version bump changes them there only.
"""
from pathlib import Path
import re

KICK = Path(__file__).resolve().parents[2] / "patches/src/main/kotlin/io/github/jordanwoodson/patches/kick"


def stock_sha256():
    """SHA-256 of the stock index.android.bundle the Hermes patches are written against."""
    found = re.findall(r'const val SOURCE_SHA256 = "([0-9a-f]{64})"', (KICK / "shared/Hermes.kt").read_text())
    assert len(found) == 1, "expected one SOURCE_SHA256 in Hermes.kt"
    return found[0]


def kick_version():
    """The one Kick version the patches target."""
    found = re.findall(r'AppTarget\(version = "([^"]+)"\)', (KICK / "shared/Constants.kt").read_text())
    assert len(found) == 1, "expected one AppTarget in Constants.kt"
    return found[0]
