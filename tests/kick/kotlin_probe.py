"""Compile a JVM probe together with the Kick Hermes helper, for bin/test-kick-modules and bin/test-kick-hermes.

The helper (patches/.../kick/shared/Hermes.kt) and the Kick constants it reads compile against Morphe's patcher
classes, taken from the Morphe Desktop jar bin/bootstrap downloads, with the Kotlin compiler the Gradle build already
fetched. Returns the classpath that runs the probe.
"""
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
KICK = ROOT / 'patches/src/main/kotlin/io/github/jordanwoodson/patches/kick'


def tools():
    """tools/ of this checkout, or of the main checkout from a git worktree (as bin/lib/common.sh does)."""
    if (ROOT / 'tools').is_dir():
        return ROOT / 'tools'
    common = subprocess.run(['git', '-C', str(ROOT), 'rev-parse', '--path-format=absolute', '--git-common-dir'],
                            capture_output=True, text=True).stdout.strip()
    return Path(re.sub(r'/\.git$', '', common)) / 'tools'


def compile_kotlin(work, probes):
    work.mkdir(parents=True, exist_ok=True)
    compilers = sorted((Path.home() / '.gradle/wrapper/dists').glob('gradle-*/**/lib/kotlin-compiler-embeddable-*.jar'))
    if not compilers:
        raise SystemExit('Run the Gradle build first; its existing Kotlin compiler is reused.')
    desktop = sorted(tools().glob('morphe-desktop-*-all.jar'))
    if not desktop:
        raise SystemExit('Morphe Desktop missing: run bin/bootstrap')
    kotlin = os.pathsep.join(map(str, compilers[-1].parent.glob('*.jar')))
    classpath = kotlin + os.pathsep + str(desktop[-1])
    output = work / 'probe.jar'
    sources = [KICK / 'shared/Hermes.kt', KICK / 'shared/Constants.kt', *probes]
    subprocess.run(['java', '-cp', kotlin, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                    '-nowarn', '-classpath', classpath, '-d', str(output), *map(str, sources)], check=True)
    return str(output) + os.pathsep + classpath


if __name__ == '__main__':
    # kotlin_probe.py <work dir> <Probe.kt> <main class> [probe arguments...]
    import sys
    work, probe, main_class, *arguments = sys.argv[1:]
    runtime = compile_kotlin(Path(work), [Path(probe)])
    raise SystemExit(subprocess.run(['java', '-cp', runtime, main_class, *arguments]).returncode)
