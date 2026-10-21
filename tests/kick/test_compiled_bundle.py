#!/usr/bin/env python3
"""Compile real source with both pinned official compilers; verify reproducibility/provenance."""
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
work = Path(os.environ.get('MORPHE_WORK', Path.home() / 'build/lurk-morphe-patches'))
work.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='compiled-hermes-', dir=work) as directory:
    directory = Path(directory)
    source = directory / 'input.js'
    source.write_text('global.lurkCompilerSmoke = 1;\n')
    for family in (98, 99):
        output = directory / f'{family}.hbc'
        result = subprocess.run(['python3', str(ROOT / 'bin/lib/compile-hermes.py'), str(family), str(source), str(output)], capture_output=True, text=True)
        assert result.returncode == 0, f'compiler integration must build HBC{family}: {result.stderr}'
        data = output.read_bytes()
        assert data[:8] == bytes.fromhex('c61fbc03c103191f') and struct.unpack_from('<I', data, 8)[0] == family
        meta = json.loads(output.with_suffix('.json').read_text())
        assert meta['sourceSha256'] == hashlib.sha256(source.read_bytes()).hexdigest()
        assert meta['bytecodeSha256'] == hashlib.sha256(data).hexdigest()
        source.write_text('global.lurkCompilerSmoke = 2;\n')
        subprocess.run(['python3', str(ROOT / 'bin/lib/compile-hermes.py'), str(family), str(source), str(output)], check=True)
        assert output.read_bytes() != data, 'changed source must replace the compiled resource'
        source.write_text('global.lurkCompilerSmoke = 1;\n')
        source.write_text('global.lurkHeaderProbe = function(){try{return global.missing();}catch(e){return 7;}};\n')
        subprocess.run(['python3', str(ROOT / 'bin/lib/compile-hermes.py'), str(family), str(source), str(output)], check=True)
        probe = output.read_bytes()
        # HBC98 has four cache bytes; HBC99 has three. Do not apply one family's
        # header layout to the other (hermes-dec 0.1.7 misreads the HBC98 flags).
        exceptions = []
        for index in range(struct.unpack_from('<I', probe, 40)[0]):
            position = 128 + 12 * index
            first, second = struct.unpack_from('<II', probe, position)
            if not probe[position + 11] & 32:
                continue
            header = (first & 0xffffff) | (((second >> 14) & 255) << 24)
            full_size = 37 if family == 98 else 36
            flags = probe[header + full_size - 1]
            if flags & 8:
                table = (header + full_size + 3) & ~3
                count = struct.unpack_from('<I', probe, table)[0]
                assert count == 1
                start, end, target = struct.unpack_from('<III', probe, table + 4)
                body, _, _, size = struct.unpack_from('<IIII', probe, header)
                assert 0 <= start < end <= size and 0 <= target < size
                assert probe[body + target] == 0x77, 'exception target must be Catch'
                exceptions.append(index)
        assert len(exceptions) == 1, f'HBC{family} full header/exception layout changed'
        source.write_text('global.lurkCompilerSmoke = 1;\n')
subprocess.run(['node', str(ROOT / 'tests/kick/test_composer.js')], cwd=ROOT, check=True)
subprocess.run(['node', str(ROOT / 'tests/kick/test_page_lifecycle.js')], cwd=ROOT, check=True)
print('PASS: pinned compiler families and fresh-source provenance')
