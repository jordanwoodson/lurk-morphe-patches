#!/usr/bin/env python3
"""Execute Anonymous chat's emitted Hermes edit: the chat page's config, built with the switch off and on.

Usage (hermes-dec Python): verify_chat_config.py stock.bundle patched.apk
Or: verify_chat_config.py stock.bundle patched.bundle manifest.json

Kick's chat page gets its config as JSON in its address, built by an anonymous function in HTMLChatWrapper (it reads
getRequestBaseHeaders and searchParams and loads https://chat.mobile.kick.com). That function's bytecode, from the
config object literal to the JSON.stringify call, runs in verify_interface.py's interpreter with the APK's edit bytes
for chat_anonymous, off and on. The closure environments are doubles: every slot holds one signed-in account (id,
kick.com slug, username) that also answers the chat settings and getRequestBaseHeaders, and the secure store's token
is a fixed string. Checked:
- off: the config carries the sign-in token, selfUser {id, slug, username} and the app's headers, as in stock;
- on: the token slot is null and selfUser undefined (JSON.stringify leaves it out), the headers and every other
  value stay, and the page would see what a signed-out app sends (stock code run without an account and token).
This doesn't run the chat page; the device check (signed out, chat still loads) is in docs/kick.md.
"""
import argparse
import json
from pathlib import Path
import sys
from zipfile import is_zipfile, ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parent))  # Also under python -I.
from test_hermes import verify as structural  # noqa: E402
from verify_interface import Bundle, UNDEFINED, check  # noqa: E402

KEY = "chat_anonymous"
TOKEN = "signed-in-token"
HEADERS = {"x-kick-app": "mobile", "x-app-platform": "Android"}


class Environment:
    """A closure environment: [account] in slot [account_slot], [holder] in every other slot."""

    def __init__(self, account_slot, account, holder):
        self.account_slot, self.account, self.holder = account_slot, account, holder

    def __getitem__(self, slot):
        return self.account if slot == self.account_slot else self.holder


ACCOUNT = {"id": 42, "streamer_channel": {"slug": "viewer"}, "username": "Viewer"}


class Holder(dict):
    """The chat settings and the API module; it prints as a name in the page's JSON."""

    def __init__(self):
        super().__init__(showMessagesTimestamps=True, textSize=16, getRequestBaseHeaders=lambda this: dict(HEADERS))


HOLDER = Holder()


def account_slot(bundle, header, start, stop):
    """The environment slot of the account the selfUser branch tests (JmpFalse), in stock code."""
    code = bundle.code(header, bundle.stock)
    window = [code[o] for o in sorted(code) if start <= o < stop]
    jumps = [i for i in window if i.inst.name == "JmpFalse"]
    check(len(jumps) == 1, "one account branch in the config")
    loads = [i for i in window if i.inst.name == "LoadFromEnvironment" and i.arg1 == jumps[0].arg2 and
             i.original_pos < jumps[0].original_pos]
    return loads[-1].arg3


def build(bundle, header, data, start, stop, token, user):
    """The config object the function builds, from its object literal up to JSON.stringify."""
    code = bundle.code(header, data)
    slot = account_slot(bundle, header, start, stop)
    registers, written = {}, set()
    for offset in sorted(code):
        if offset < start or offset >= stop:
            continue
        instruction = code[offset]
        if instruction.inst.name == "LoadFromEnvironment" and instruction.arg2 not in written:
            registers[instruction.arg2] = Environment(slot, user, HOLDER)
        if instruction.inst.name == "PutOwnBySlotIdx" and instruction.arg2 not in written:
            registers.setdefault(instruction.arg2, token)  # The token, read before the literal.
        if instruction.inst.operands and instruction.inst.operands[0].operand_type.name == "Reg8" and \
                instruction.inst.name not in ("PutOwnBySlotIdx", "JmpFalse", "JmpTrue"):
            written.add(instruction.arg1)
    _, registers = bundle.run(header, data, registers=registers, start=start, stop={stop})
    literal = code[start]
    return registers[literal.arg1]


def stringify(config):
    """What JSON.stringify gives the page: undefined values are left out."""
    return json.dumps({key: value for key, value in config.items() if value is not UNDEFINED}, sort_keys=True,
                      default=lambda value: "holder" if isinstance(value, Holder) else repr(value))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("stock", type=Path)
    parser.add_argument("patched", type=Path)
    parser.add_argument("manifest", nargs="?", type=Path)
    args = parser.parse_args()
    if is_zipfile(args.patched):
        with ZipFile(args.patched) as apk:
            embedded = apk.read("assets/index.android.bundle")
            manifest = json.loads(apk.read("assets/lurk-hermes.json"))
    else:
        embedded = args.patched.read_bytes()
        manifest = json.loads(args.manifest.read_text())
    stock = args.stock.read_bytes()
    structural(stock, embedded, manifest)
    bundle = Bundle(stock, embedded, manifest)
    edits = [edit for edit in manifest["edits"] if edit["key"] == KEY]
    check(len(edits) == 1 and edits[0]["default"] is False, "one chat_anonymous edit, off by default")
    edit = edits[0]
    owners = [h for h in bundle.reader.function_headers
              if h.offset <= edit["offset"] < h.offset + h.bytecodeSizeInBytes]
    check(len(owners) == 1, "one function carries the edit")
    header = owners[0]
    code = bundle.code(header, stock)
    names = {bundle.strings[i.arg4] for i in code.values() if i.inst.name in ("GetById", "GetByIdShort", "TryGetById")}
    check({"getRequestBaseHeaders", "searchParams", "token"} <= names, "it is the chat page config builder")
    literals = [o for o, i in code.items() if i.inst.name.startswith("NewObjectWithBuffer") and
                {"'token'", "'selfUser'", "'headers'"} <= set(bundle.reader.object_shape_keys[i.arg2])]
    check(len(literals) == 1, "one config object literal with token, selfUser and headers")
    start = literals[0]
    stop = min(o for o, i in code.items() if o > start and i.inst.name == "Call2")

    off, on = bundle.state({KEY: "false"}), bundle.state({KEY: "true"})
    stock_config = build(bundle, header, off, start, stop, TOKEN, ACCOUNT)
    check(stock_config["token"] == TOKEN, "off: the config carries the sign-in token")
    check(stock_config["selfUser"] == {"id": 42, "slug": "viewer", "username": "Viewer"},
          "off: the config carries the account as selfUser")
    check(stock_config["headers"] == HEADERS, "off: the config carries the app's request headers")

    guest = build(bundle, header, on, start, stop, TOKEN, ACCOUNT)
    check(guest["token"] is None, "on: the token slot keeps the literal's null")
    check(guest["selfUser"] is UNDEFINED and "selfUser" not in json.loads(stringify(guest)),
          "on: selfUser is undefined, so the page's JSON has none")
    check(guest["headers"] == HEADERS, "on: the app's request headers stay")
    rest = {key for key in stock_config if key not in ("token", "selfUser")}
    check(all(guest[key] == stock_config[key] for key in rest), "on: every other config value is unchanged")
    check(TOKEN not in stringify(guest) and "Viewer" not in stringify(guest) and "viewer" not in stringify(guest),
          "on: the page's address holds neither the token nor the account")

    # What a signed-out app sends: stock code with no account and no token.
    signed_out = build(bundle, header, off, start, stop, UNDEFINED, UNDEFINED)
    check(signed_out["selfUser"] is UNDEFINED, "signed out: no selfUser")
    check(json.loads(stringify(guest)).get("token") is None and json.loads(stringify(signed_out)).get("token") is None,
          "on and signed out alike: the page gets no token to authenticate with")
    print("PASS: Anonymous chat builds the chat page config without the token and the account, keeping the rest")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        sys.exit(f"FAIL: {e}")
