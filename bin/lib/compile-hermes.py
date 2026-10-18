#!/usr/bin/env python3
"""Build one small runtime bundle with the pinned, mise-managed official Hermes compiler."""
import hashlib
import json
from pathlib import Path
import platform
import re
import struct
import subprocess
import sys

VERSIONS = {98: '250829098.0.19', 99: '260318099.0.4'}

def main():
    family, source, output = int(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    version = VERSIONS[family]
    root = subprocess.check_output(['mise', 'where', 'npm:hermes-compiler@' + version], text=True).strip()
    package_file = Path(subprocess.check_output(['node', '-e',
        "process.stdout.write(require.resolve('hermes-compiler/package.json',{paths:[process.argv[1]]}))", root], text=True))
    package = json.loads(package_file.read_text())
    if package.get('name') != 'hermes-compiler' or package.get('version') != version or package.get('repository', {}).get('url') != 'git+ssh://git@github.com/facebook/hermes.git':
        raise ValueError('unexpected compiler package provenance')
    binary = package_file.parent / 'hermesc' / {'Linux':'linux64-bin', 'Darwin':'osx-bin', 'Windows':'win64-bin'}[platform.system()] / ('hermesc.exe' if platform.system() == 'Windows' else 'hermesc')
    banner = subprocess.check_output([str(binary), '-version'], text=True)
    if not re.search(r'HBC bytecode version:\s*' + str(family) + r'\b', banner):
        raise ValueError('compiler bytecode family mismatch')
    output.parent.mkdir(parents=True, exist_ok=True)
    # Compile directly to the Gradle output. Failure removes output so stale bytes can never be packaged.
    try:
        subprocess.run([str(binary), '-O', '-emit-binary', '-out', str(output), str(source)], check=True)
        data = output.read_bytes()
        if len(data) < 32 or data[:8] != bytes.fromhex('c61fbc03c103191f') or struct.unpack_from('<I', data, 8)[0] != family:
            raise ValueError('emitted HBC header mismatch')
        meta = {'format':1, 'version':family, 'compiler':version,
                'compilerRepository':'https://github.com/facebook/hermes',
                'compilerSha256':hashlib.sha256(binary.read_bytes()).hexdigest(),
                'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),
                'bytecodeSha256':hashlib.sha256(data).hexdigest()}
        output.with_suffix('.json').write_text(json.dumps(meta, sort_keys=True) + '\n')
    except BaseException:
        output.unlink(missing_ok=True)
        output.with_suffix('.json').unlink(missing_ok=True)
        raise

if __name__ == '__main__':
    main()
