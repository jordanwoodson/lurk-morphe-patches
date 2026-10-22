"""The obfuscated names the patches matched in a patched APK, from its assets/lurk/locators.json.

Keys look like twitch.match.spade_event; values are smali descriptors: a type (Ljn0;), a method
(Ljn0;->d(Ljava/lang/String;)V) or a field (Lgd9;->m:Log9;). A verifier passes the name it was written
against (Twitch 31.7.0's) as the fallback, used while an APK or apktool directory has no such file or key.
"""
import json
import zipfile
from pathlib import Path


class Locators:
    def __init__(self, source):
        """[source] is a patched APK or its apktool output directory."""
        self.values = {}
        path = Path(source)
        if path.is_dir():
            asset = path / "assets/lurk/locators.json"
            if asset.is_file():
                self.values = json.loads(asset.read_text())
        elif zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as z:
                if "assets/lurk/locators.json" in z.namelist():
                    self.values = json.loads(z.read("assets/lurk/locators.json"))

    def describe(self):
        return "names from the APK's locators" if self.values else "no locators in the APK: 31.7.0 names"

    def type(self, key, fallback):
        """The class of [key] (a type, or a member's defining class) without L and ;, as in smali paths."""
        value = self.values.get(key)
        if value is None:
            return fallback
        return value.split("->")[0][1:-1]

    def method(self, key, fallback_class, fallback_signature):
        """(class, name(parameters)return) of the method locator [key]."""
        value = self.values.get(key)
        if value is None or "->" not in value or "(" not in value:
            return fallback_class, fallback_signature
        owner, signature = value.split("->", 1)
        return owner[1:-1], signature

    def field(self, key, fallback):
        """The field name of the field locator [key]."""
        value = self.values.get(key)
        if value is None or "->" not in value or ":" not in value:
            return fallback
        return value.split("->", 1)[1].split(":", 1)[0]

    def fields(self, prefix, fallback):
        """The field names of every locator under [prefix], sorted, or [fallback] when there are none."""
        names = sorted(value.split("->", 1)[1].split(":", 1)[0] for key, value in self.values.items()
                       if key.startswith(prefix + ".") and "->" in value)
        return names or fallback
