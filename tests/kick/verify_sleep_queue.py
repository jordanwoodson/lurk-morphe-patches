#!/usr/bin/env python3
"""Check actual emitted Kick sleep-queue guard and register flow after apktool decode.

Usage: python3 -I tests/kick/verify_sleep_queue.py <apktool-dir>
Requires a Kick APK patched with Sleep timer. APK-free tests separately exercise the guard's behavior.
"""
from pathlib import Path
import re
import sys


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    files = list(Path(sys.argv[1]).glob('smali*/com/kick/mobile/kickivsplayerv2/KickIvsPlayerV2View.smali'))
    assert len(files) == 1, 'expected exactly one Kick player view'
    methods = re.findall(r'\.method[^\n]*\n.*?\.end method', files[0].read_text(), re.S)
    executors = [method for method in methods if '"Player operation rejected"' in method]
    assert len(executors) == 1, 'expected exactly one player executor enqueue method'
    method = executors[0]
    guard = 'Lio/github/jordanwoodson/extension/shared/SleepTimer;->guardPauseOperation'
    assert method.count(guard) == 1, 'missing or duplicate expiry guard in player executor'
    pattern = (
        r'invoke-static/range\s+\{v(\d+)\s+\.\.\s+v\1\},\s*' + re.escape(guard)
        + r'\(Ljava/lang/Runnable;\)Ljava/lang/Runnable;\s+'
        + r'move-result-object\s+v\1\s+'
        + r'invoke-interface\s+\{v\d+,\s*v\1\},\s*'
        + r'Ljava/util/concurrent/ExecutorService;->execute\(Ljava/lang/Runnable;\)V'
    )
    assert re.search(pattern, method), 'guard result does not reach the actual executor Runnable argument'
    print('PASS Kick sleep queue: emitted expiry guard feeds the actual executor argument')


if __name__ == '__main__':
    main()
