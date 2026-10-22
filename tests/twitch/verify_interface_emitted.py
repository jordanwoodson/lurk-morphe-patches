#!/usr/bin/env python3
"""Check pinned native UI targets and preservation controls in stock/patched apktool output.

Usage: python3 tests/twitch/verify_interface_emitted.py stock-apktool patched-apktool
Version-specific class/field names belong in this independent artifact test, never fingerprints. The ones
the patches match come from the patched output's locators (assets/lurk/locators.json); the names written
here are Twitch 31.7.0's R8 output, used while the output has none. The preservation controls (classes no
patch touches) stay pinned.
"""
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from locators import Locators  # noqa: E402

def source(root, relative):
    candidates = list(root.glob("smali*/" + relative))
    assert len(candidates) == 1, (relative, candidates)
    return candidates[0].read_text()

def method(text, signature):
    candidates = [body for declaration, body in re.findall(r"^\.method ([^\n]+)\n(.*?)^\.end method", text, re.M | re.S)
                  if declaration.endswith(signature)]
    assert len(candidates) == 1, signature
    return candidates[0]

def executable_source(text):
    # Dex rewriting can move debug line markers across instruction boundaries. Preserve every
    # instruction, field, annotation and label while ignoring that non-executable metadata.
    return "\n".join(line.strip() for line in text.splitlines()
                     if line.strip() and not line.strip().startswith(".line "))

def main():
    stock, patched = map(Path, sys.argv[1:])
    loc = Locators(patched)
    print(loc.describe())
    helper = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchInterface;"
    states = [(loc.type("twitch.match.chat_toolbar_state", "px70") + ".smali", loc.fields("twitch.ui_flag.chat_toolbar_state", ["k", "m"])),
              (loc.type("twitch.match.one_chat_state", "zas") + ".smali", loc.fields("twitch.ui_flag.one_chat_state", ["e", "k"]))]
    for filename, flags in states:
        original = source(stock, filename)
        emitted = source(patched, filename)
        assert helper not in original, "Stock negative unexpectedly contains Lurk"
        assert emitted.count(helper + "->bitsVisible(Z)Z") == len(flags), filename
        constructor = next(body for declaration, body in re.findall(r"^\.method ([^\n]+)\n(.*?)^\.end method", emitted, re.M | re.S)
                           if declaration.startswith("public constructor <init>"))
        instructions = [line.strip() for line in constructor.splitlines()
                        if line.strip() and not line.lstrip().startswith((".", "#", ":"))]
        for flag in flags:
            position = next(i for i, line in enumerate(instructions) if line.endswith(f"->{flag}:Z") and line.startswith("iput-boolean"))
            register = instructions[position].split()[1].rstrip(",")
            assert instructions[position - 1] == f"move-result {register}"
            assert "->bitsVisible(Z)Z" in instructions[position - 2]
        assert constructor.count("return-void") == 1, "UI-state constructor exit changed"
    profile = source(patched, loc.type("twitch.match.profile_tab_routes", "cov") + ".smali")
    assert helper + "->clipTabs(Ljava/lang/Object;Landroid/os/Bundle;)Ljava/lang/Object;" in profile
    assert re.search(r"invoke-static \{p1, p4\}, " + re.escape(helper) +
                     r"->clipTabs\(Ljava/lang/Object;Landroid/os/Bundle;\)Ljava/lang/Object;", profile), "Profile tabs lost original Bundle context"
    assert "check-cast p1, Lkotlin/enums/EnumEntries;" in profile or "check-cast v" in profile
    owner, signature = loc.method("twitch.hide_subscription_upsells.subscribe_renderer", "vs20", "w(Lus20;)V")
    subscribe = loc.type("twitch.match.subscribe_state", "rs20")
    gift = loc.type("twitch.match.gift_state", "ps20")
    renderer = method(source(patched, owner + ".smali"), signature)
    assert re.search(rf"instance-of [vp]\d+, [vp]\d+, L{subscribe};", renderer)
    assert re.search(rf"instance-of [vp]\d+, [vp]\d+, L{gift};", renderer)
    assert "->setVisibility(I)V" in renderer and "hide_subscription_upsells" in renderer
    assert "hide_subscription_upsells" not in method(source(stock, owner + ".smali"), signature)
    # Account entitlement, received chat events and clip deep links are preservation controls.
    for filename in ["tv/twitch/android/models/UserModel.smali", "prr.smali", subscribe + ".smali", gift + ".smali"]:
        assert executable_source(source(stock, filename)) == executable_source(source(patched, filename)), filename + " changed unexpectedly"
    route = loc.type("twitch.hide_subscription_upsells.turbo_dialog_route", "le90")
    assert "hide_subscription_upsells" in source(patched, route + ".smali"), "Turbo app-start route was not guarded"
    account = source(patched, loc.type("twitch.match.account_subscriptions_section", "smj") + ".smali")
    account_section = next(body for declaration, body in re.findall(r"^\.method ([^\n]+)\n(.*?)^\.end method", account, re.M | re.S)
                           if "0x7f151833" in body and "0x7f151834" in body)
    assert "hide_subscription_upsells" in account_section, "Native account Turbo promotion remains"
    assert re.search(r"move/from16 v\d+, p0", account_section), "Must retain actual Turbo subscription input"
    for filename in ["dj50.smali", loc.type("twitch.match.turbo_app_start_action", "hgr") + ".smali"]:
        assert executable_source(source(stock, filename)) == executable_source(source(patched, filename)), filename + " changed unexpectedly"
    # Selection/argument parsing and the existing native clip bridge are preservation controls.
    for filename in ["e82.smali", "sb1.smali", "aqv.smali", "ec8.smali", "lw7.smali",
                     "tv/twitch/android/feature/discovery/feed/rn/bridge/TwitchRNHostNavigationModule.smali"]:
        assert executable_source(source(stock, filename)) == executable_source(source(patched, filename)), filename + " clip navigation changed unexpectedly"
    print("PASS: native UI flag writes, profile tabs, selective subscription renderer, Turbo route and stock preservation controls")

if __name__ == "__main__":
    main()
