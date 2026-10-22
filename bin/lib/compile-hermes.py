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
# SHA-256 of the hermesc each package ships per platform, recorded from the npm packages above on
# 2026-10-22. A different binary under the same version means a changed or tampered package, and the
# build stops. Moving a family to another compiler version means recording its three hashes here.
COMPILER_SHA256 = {
    98: {'linux64-bin': '925ddf1bc2f17578e8af5df391c9d84dbd98441540fb43257aac3ef75c54b749',
         'osx-bin': 'fb0887abe00d833c7fbd6617e6b698a8e52065278cbf5d34822839e5f2dc0830',
         'win64-bin': 'eb0a9422d83b6429d6916d8653c4904234c1fddf58a0ee29effe4ebf2246fe02'},
    99: {'linux64-bin': '44d76b1e385b6584380e95e5c63d9f98a4f812cd3cc7aa0500bacd3c39fe0987',
         'osx-bin': '1ce56f036bdadab6027cfd22a43e40640212562746b1d3ec29b62ca4b13f9a23',
         'win64-bin': '7cb85cd14f918a5b81612ca2d273f646967ed59a615ee4daf7f2c51b158864df'},
}

def main():
    family, source, output = int(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    version = VERSIONS[family]
    root = subprocess.check_output(['mise', 'where', 'npm:hermes-compiler@' + version], text=True).strip()
    package_file = Path(subprocess.check_output(['node', '-e',
        "process.stdout.write(require.resolve('hermes-compiler/package.json',{paths:[process.argv[1]]}))", root], text=True))
    package = json.loads(package_file.read_text())
    if package.get('name') != 'hermes-compiler' or package.get('version') != version or package.get('repository', {}).get('url') != 'git+ssh://git@github.com/facebook/hermes.git':
        raise ValueError('unexpected compiler package provenance')
    folder = {'Linux':'linux64-bin', 'Darwin':'osx-bin', 'Windows':'win64-bin'}[platform.system()]
    binary = package_file.parent / 'hermesc' / folder / ('hermesc.exe' if platform.system() == 'Windows' else 'hermesc')
    compiler_sha256 = hashlib.sha256(binary.read_bytes()).hexdigest()
    if compiler_sha256 != COMPILER_SHA256[family][folder]:
        raise ValueError(f'{binary} has SHA-256 {compiler_sha256}, not the '
                         f'{COMPILER_SHA256[family][folder]} pinned for hermes-compiler {version}')
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
                'compilerSha256':compiler_sha256,
                'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),
                'bytecodeSha256':hashlib.sha256(data).hexdigest()}
        output.with_suffix('.json').write_text(json.dumps(meta, sort_keys=True) + '\n')
    except BaseException:
        output.unlink(missing_ok=True)
        output.with_suffix('.json').unlink(missing_ok=True)
        raise

if __name__ == '__main__':
    main()
