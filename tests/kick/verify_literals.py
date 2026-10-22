#!/usr/bin/env python3
"""Check that Kick's literal-buffer edits change only the literals they mean to.

Usage (with hermes-dec installed in this Python environment):
    python tests/kick/verify_literals.py stock.bundle patched.apk
    python tests/kick/verify_literals.py stock.bundle manifest.json

Hermes keeps array and object literal values in one shared buffer, and two literals can read the same
bytes. For every edit outside function bytecode, this decodes all functions, finds each array or object
literal whose values cover an edited byte, and requires those to be exactly the literals EXPECTED for
the edit's setting. It then applies each value the setting can take and re-parses those literals: tags,
count and length must stay the same, and string values must be valid string IDs. It reads files only.
"""
import argparse
from dataclasses import dataclass
from io import BytesIO
import json
from pathlib import Path
import struct
import sys
from zipfile import is_zipfile, ZipFile

from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_opcodes import hbc99

sys.path.insert(0, str(Path(__file__).resolve().parent))  # Also under python -I.
from pins import stock_sha256  # noqa: E402

STOCK_SHA256 = stock_sha256()
LENGTHS = {instruction.opcode: instruction.binary_size for instruction in hbc99._instructions}
# Opcode: (kind, operand offsets of shape-or-count and value-buffer index, operand sizes).
LITERALS = {
    0x01: ("object", 2, 2, 4, 2),  # NewObjectWithBuffer: shape u16, values u16
    0x02: ("object", 2, 4, 6, 4),  # NewObjectWithBufferLong: shape u32, values u32
    0x03: ("object", 3, 4, 7, 4),  # NewObjectWithBufferAndParent: parent reg, shape u32, values u32
    0x07: ("array", 4, 2, 6, 2),   # NewArrayWithBuffer: size u16, count u16, values u16
    0x08: ("array", 4, 2, 6, 4),   # NewArrayWithBufferLong: size u16, count u16, values u32
}
SWITCHES = {0xa7: 2, 0xa8: 6}  # UIntSwitchImm, StringSwitchImm: operand with the jump table offset


@dataclass(frozen=True)
class Literal:
    function: str
    offset: int
    kind: str
    keys: tuple
    start: int
    end: int


# Per setting, what the literals reading its edited bytes must be.
EXPECTED = {
    # PlayerConfig.controls.skip.scale: the `vod` and `live-vod` arrays, [10, 10, 10] each.
    "vod_skip_seconds": lambda found: len(found) == 2 and len({item.function for item in found}) == 1 and
        all(item.kind == "array" and len(item.keys) == 3 for item in found),
    # Colors.surface.default, read once.
    "amoled_black": lambda found: len(found) == 1 and found[0].kind == "object" and
        found[0].keys[:3] == ("highest", "base", "lowest"),
}


def u(data, offset, size):
    return int.from_bytes(data[offset:offset + size], "little")


def literal_start(data):
    """The literal value buffer's file offset, computed from the header as HermesBundle does."""
    def align(value):
        return (value + 3) & ~3
    cursor = 128 + u(data, 40, 4) * 12
    cursor = align(cursor) + u(data, 44, 4) * 4
    cursor = align(cursor) + u(data, 48, 4) * 4
    cursor = align(cursor) + u(data, 52, 4) * 4
    cursor = align(cursor) + u(data, 56, 4) * 8
    cursor = align(cursor) + u(data, 60, 4)
    return align(cursor)


def parse(values, start, count):
    """Serialized literal values from `start`: (tag, value) items and the end offset. v98+ tags."""
    items, cursor = [], start
    while len(items) < count:
        tag = values[cursor]
        kind = (tag >> 4) & 7
        if tag & 0x80:
            length = ((tag & 0x0f) << 8) | values[cursor + 1]
            cursor += 2
        else:
            length = tag & 0x0f
            cursor += 1
        size = {0: 0, 1: 0, 2: 0, 3: 8, 4: 4, 5: 2, 6: 0, 7: 4}[kind]
        for _ in range(length):
            raw = values[cursor:cursor + size]
            value = struct.unpack("<d", raw)[0] if kind == 3 else int.from_bytes(raw, "little")
            items.append((kind, value))
            cursor += size
    assert len(items) == count, "literal item count overrun"
    return items, cursor


def literals(reader, data, values):
    """Every array or object literal instruction in the bundle, with the byte range of its values."""
    result = []
    for header in reader.function_headers:
        body = data[header.offset:header.offset + header.bytecodeSizeInBytes]
        name = reader.strings[header.functionName]
        position, end = 0, len(body)
        while position < end:
            opcode = body[position]
            length = LENGTHS[opcode]
            if opcode in SWITCHES:
                end = min(end, position + u(body, position + SWITCHES[opcode], 4))
            if opcode in LITERALS:
                kind, first, first_size, index, index_size = LITERALS[opcode]
                number = u(body, position + first, first_size)
                buffer_index = u(body, position + index, index_size)
                keys = (tuple(key.strip("'\"") for key in reader.object_shape_keys[number]) if kind == "object"
                        else tuple(range(number)))
                _, stop = parse(values, buffer_index, len(keys))
                result.append(Literal(name, position, kind, keys, buffer_index, stop))
            position += length
    return result


def verify(stock, manifest):
    assert manifest["format"] == 2 and manifest["sourceSha256"] == STOCK_SHA256, "Unexpected manifest"
    reader = HBCReader()
    reader.read_whole_file(BytesIO(stock))
    start = literal_start(stock)
    values = reader.literal_values
    assert stock[start:start + len(values)] == values, "Literal buffer offset differs from hermes-dec's"
    in_code = [(header.offset, header.offset + header.bytecodeSizeInBytes) for header in reader.function_headers]
    data_edits = [edit for edit in manifest["edits"]
                  if not any(low <= edit["offset"] < high for low, high in in_code)]
    if not data_edits:
        print("PASS: no literal edits in this manifest")
        return
    found = literals(reader, stock, values)
    assert len(found) > 50000, "too few literal instructions decoded"
    checked = []
    for key in sorted({edit["key"] for edit in data_edits}):
        edits = [edit for edit in data_edits if edit["key"] == key]
        ranges = [(edit["offset"] - start, edit["offset"] - start + len(edit["original"]) // 2) for edit in edits]
        for low, high in ranges:
            assert 0 <= low < high <= len(values), f"{key}: edit outside the literal buffer"
        covering = [item for item in found if any(item.start < high and low < item.end for low, high in ranges)]
        assert key in EXPECTED, f"{key}: no expected literals recorded"
        assert EXPECTED[key](covering), f"{key}: unexpected literals read the edited bytes: {covering}"
        choices = sorted({value for edit in edits for value in edit.get("values", {})}) or ["on"]
        for choice in ["off"] + choices:
            edited = bytearray(values)
            for edit, (low, high) in zip(edits, ranges):
                value = edit.get("values", {}).get(choice) or (edit.get("replacement") if choice == "on" else None)
                if value:
                    edited[low:high] = bytes.fromhex(value)
            shown = []
            for item in covering:
                before, before_end = parse(values, item.start, len(item.keys))
                after, after_end = parse(edited, item.start, len(item.keys))
                assert after_end == before_end, f"{key}={choice}: literal length changed"
                assert [tag for tag, _ in after] == [tag for tag, _ in before], f"{key}={choice}: tags changed"
                for tag, value in after:
                    assert tag not in (4, 5) or value < len(reader.strings), f"{key}={choice}: invalid string"
                shown.append([reader.strings[value] if tag in (4, 5) else value for tag, value in after][:3])
            checked.append(f"{key}={choice} {shown}")
    for line in checked:
        print("  " + line)
    print(f"PASS: {len(data_edits)} literal edits; only the expected literals read them; every value keeps "
          "each literal's tags, count and length")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stock", type=Path)
    parser.add_argument("patched", type=Path, help="patched APK, or the manifest JSON")
    args = parser.parse_args()
    if is_zipfile(args.patched):
        with ZipFile(args.patched) as apk:
            manifest = json.loads(apk.read("assets/lurk-hermes.json"))
    else:
        manifest = json.loads(args.patched.read_text())
    verify(args.stock.read_bytes(), manifest)
