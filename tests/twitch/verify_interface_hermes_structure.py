#!/usr/bin/env python3
"""Independent Hermes decoder boundary checks. Run with the installed hermes-dec Python.

Usage: verify_interface_hermes_structure.py [bundle manifest]
Defaults to bin/test-twitch-interface-hermes output under $MORPHE_WORK (default ~/build/lurk-morphe-patches).
"""
import hashlib
import io
import json
import os
from pathlib import Path
import sys
from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

root = Path(os.environ.get("MORPHE_WORK", Path.home() / "build/lurk-morphe-patches")) / "twitch-interface"
base = Path(sys.argv[1]).read_bytes() if len(sys.argv) > 1 else (root / "interface.bundle").read_bytes()
manifest = json.loads((Path(sys.argv[2]) if len(sys.argv) > 2 else root / "interface-manifest.json").read_text())
edits = manifest["edits"]
keys = sorted({edit["key"] for edit in edits})
initial = HBCReader()
initial.read_whole_file(io.BytesIO(base))
ids = {index for index, header in enumerate(initial.function_headers)
       if any(header.offset <= edit["offset"] < header.offset + header.bytecodeSizeInBytes for edit in edits)}
for mode, enabled in [("all-on", set(keys)), ("all-off", set()), *((key, {key}) for key in keys)]:
    data = bytearray(base)
    for edit in edits:
        value = bytes.fromhex(edit["replacement"] if edit["key"] in enabled else edit["original"])
        data[edit["offset"]:edit["offset"] + len(value)] = value
    data[-20:] = hashlib.sha1(data[:-20]).digest()
    reader = HBCReader()
    reader.read_whole_file(io.BytesIO(data))
    for index in sorted(ids):
        header = reader.function_headers[index]
        instructions = list(parse_hbc_bytecode(header, reader))
        boundaries = {instruction.original_pos for instruction in instructions}
        assert instructions[-1].next_pos == header.bytecodeSizeInBytes, (mode, index, "instruction end")
        for instruction in instructions:
            for target in getattr(instruction, "switch_jump_table", ()) or ():
                assert target in boundaries, (mode, index, instruction, target)
            for operand_index, operand in enumerate(instruction.inst.operands, 1):
                if operand.operand_type.name in ("Addr8", "Addr32"):
                    target = instruction.original_pos + getattr(instruction, "arg" + str(operand_index))
                    assert target in boundaries, (mode, index, instruction, target)
    print(f"PASS independent hermes-dec: {mode}, {len(ids)} edited functions, complete instructions and branch targets")
