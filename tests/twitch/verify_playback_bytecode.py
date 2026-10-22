#!/usr/bin/env python3
"""Check emitted hooks and execute the small native preference readers against saved/default values.

Usage: python3 tests/twitch/verify_playback_bytecode.py STOCK_APKTOOL PATCHED_APKTOOL
The interpreter reads the emitted getter instructions, rather than reproducing the patched getter.
"""
from pathlib import Path
import re
import sys

PLAYBACK = 'Lio/github/jordanwoodson/extension/shared/twitch/TwitchPlayback;'
LINKS = 'Lio/github/jordanwoodson/extension/shared/twitch/TwitchLinks;'

def methods(root):
    result = []
    for path in Path(root).glob('smali*/**/*.smali'):
        source = path.read_text()
        owner = re.search(r'^\.class [^\n]*?(L[^\s]+;)$', source, re.M).group(1)
        for method in source.split('.method ')[1:]:
            signature, body = method.split('\n', 1)
            body = body.split('.end method')[0]
            result.append((owner, signature, body))
    return result

def unique(all_methods, predicate):
    matches = [m for m in all_methods if predicate(m)]
    assert len(matches) == 1, [(m[0], m[1]) for m in matches]
    return matches[0]

# ClipQuality strings (no frame rates, no 160p) for each live/VOD value.
CLIP_QUALITIES = {'chunked': 'source', '1080p60': '1080', '1080p': '1080', '720p60': '720', '720p': '720',
                  '480p': '480', '360p': '360', '160p': '240'}

def execute_reader(method, arguments, saved, enabled=True, quality='chunked'):
    """Only the straight-line instruction set of the two real getters, fail closed on new code."""
    registers = {'p0': object(), **arguments}
    result = None
    for raw in method[2].splitlines():
        line = raw.strip()
        if not line or line.startswith(('.', '#', ':')):
            continue
        if line.startswith('const-string'):
            match = re.fullmatch(r'const-string(?:/jumbo)? ([vp]\d+), "(.*)"', line)
            registers[match[1]] = match[2]
        elif line.startswith('sget-object'):
            match = re.fullmatch(r'sget-object ([vp]\d+), .*->([^:]+):.*', line)
            registers[match[1]] = match[2]
        elif line.startswith('invoke-'):
            call = re.fullmatch(r'invoke-[^ ]+ \{(.*)\}, (.*)', line)
            argnames = [v.strip() for v in call[1].split(',') if v.strip()]
            if '..' in call[1]:
                begin, end = call[1].split('..')
                assert begin.strip() == end.strip(), line
                argnames = [begin.strip()]
            args = [registers[v] for v in argnames]
            target = call[2]
            if target.endswith('->getClass()Ljava/lang/Class;'):
                result = object()
            elif target == 'Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;':
                result = args[0] + args[1]
            elif target.startswith(PLAYBACK + '->qualityDefault('):
                result = args[0] if quality is None else quality
            elif target.startswith(PLAYBACK + '->qualityPreferenceDefault('):
                result = args[0] if quality is None else CLIP_QUALITIES[quality] if args[1] == '_clip' else quality
            elif target.startswith(PLAYBACK + '->autoplayDefault('):
                result = 'never' if enabled else args[0]
            elif target.endswith('(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;'):
                result = saved.get(args[-2], args[-1])
            elif line.startswith('invoke-static') and 'Ljava/lang/String;L' in target:
                # Enum parser receives persisted key and the original Always fallback.
                result = {'always': 'Always', 'wifi_only': 'WifiOnly', 'never': 'Never'}.get(args[0], args[1])
            else:
                raise AssertionError('Unsupported getter call: ' + line)
        elif line.startswith('move-result-object'):
            registers[line.split()[1]] = result
        elif line.startswith('return-object'):
            return registers[line.split()[1]]
        else:
            raise AssertionError('Unsupported getter instruction: ' + line)
    raise AssertionError('Getter did not return')

def verify_countdown_control_flow(method):
    """Follow emitted branch labels with a bound String in p1, as on the real VOD path."""
    code, labels = [], {}
    for raw in method[2].splitlines():
        line = raw.strip()
        if not line or line.startswith(('.', '#')):
            continue
        if line.startswith(':'):
            labels[line] = len(code)
        else:
            code.append(line)
    start = next(i for i, line in enumerate(code) if PLAYBACK + '->showManualRecommendation(' in line)
    branch = code[start + 2]
    assert branch.startswith('if-eqz '), branch
    target = labels[branch.split(',')[-1].strip()]
    assert target > start, f'disabled countdown jumps backward: {branch} -> {code[target]}'
    assert code[target].startswith('iget-object ') and code[target].endswith('Landroid/widget/ProgressBar;'), (
        'disabled countdown must enter the original ProgressBar load', branch, code[target])
    for enabled in [False, True]:
        registers = {'p0': 'owner', 'p1': 'already-bound channel String', 'v1': 'container', 'v2': 0}
        result, disposed, shown, stock = None, 0, 0, False
        pc = start
        for _ in range(20):
            line = code[pc]
            pc += 1
            if line.startswith('invoke-static ') and '->showManualRecommendation(' in line:
                result = enabled
            elif line.startswith('move-result '):
                registers[line.split()[1]] = result
            elif line.startswith(('move-object/from16 ', 'move-object ')):
                target, source = line.split(' ', 1)[1].split(', ')
                registers[target] = registers[source]
            elif line.startswith('if-eqz '):
                register, label = line.removeprefix('if-eqz ').split(', ')
                if not registers[register]:
                    pc = labels[label]
            elif line.startswith('iget-object '):
                match = re.fullmatch(r'iget-object ([vp]\d+), ([vp]\d+), (.*)', line)
                assert registers[match[2]] == 'owner', line
                if match[3].endswith('Landroid/widget/ProgressBar;'):
                    registers[match[1]] = 'progressBar'
                elif match[3].endswith('Lio/reactivex/disposables/b;'):
                    registers[match[1]] = 'timer'
                else:
                    raise AssertionError('Unexpected countdown field: ' + line)
            elif line.startswith('invoke-interface ') and '->dispose()V' in line:
                register = line.split('{')[1].split('}')[0].strip()
                assert registers[register] == 'timer'
                disposed += 1
            elif line.startswith('const/4 '):
                register, value = line.removeprefix('const/4 ').split(', ')
                registers[register] = int(value, 16)
            elif re.search(r'BaseViewDelegate;->\w+\(I\)V', line):
                shown += 1
            elif 'ProgressBar;->setProgress(I)V' in line:
                register = line.split('{')[1].split(',')[0].strip()
                assert registers[register] == 'progressBar', 'off path passed the previously bound String to ProgressBar'
                stock = True
                break
            elif line == 'return-void':
                break
            else:
                raise AssertionError('Countdown branch escaped its intended continuation: ' + line)
        else:
            raise AssertionError('Countdown gate did not reach stock playback or return')
        assert (disposed, shown, stock) == ((1, 1, False) if enabled else (0, 0, True))

def main():
    stock, patched = map(methods, sys.argv[1:3])
    def autoplay(m):
        return '"autoplay_enabled"' in m[2] and m[1].split('(')[1].startswith(')')
    stock_auto = unique(stock, autoplay)
    patched_auto = unique(patched, autoplay)
    assert execute_reader(stock_auto, {}, {}) == 'Always', 'stock negative control changed'
    assert execute_reader(patched_auto, {}, {}) == 'Never'
    assert execute_reader(patched_auto, {}, {}, enabled=False) == 'Always'
    for saved_value, expected in [('always', 'Always'), ('wifi_only', 'WifiOnly'), ('never', 'Never')]:
        assert execute_reader(patched_auto, {}, {'autoplay_enabled': saved_value}) == expected
    auto_hooks = [m for m in patched if PLAYBACK + '->autoplayDefault(' in m[2]]
    assert len(auto_hooks) == 2, 'both initial and observer fallbacks must be patched'
    dispatch = unique(patched, lambda m: '"player_rec_autoplay"' in m[2] and '"player_rec_select"' in m[2])
    dispatch_stock = unique(stock, lambda m: m[:2] == dispatch[:2])
    assert '"autoplay_next_video_off"' in dispatch[2]
    automatic = dispatch[2].index('"player_rec_autoplay"')
    gate = dispatch[2].index('"autoplay_next_video_off"')
    assert gate < automatic
    assert 'return-object' in dispatch[2][gate:automatic]
    # The manual branch and all its dispatch instructions are identical to stock.
    def manual_branch(body):
        start = body.index('"player_rec_select"')
        end = body.index('goto ', start)
        return [l.strip() for l in body[start:end].splitlines() if not l.strip().startswith('.line') and l.strip()]
    assert manual_branch(dispatch[2]) == manual_branch(dispatch_stock[2])
    countdown = unique(patched, lambda m: PLAYBACK + '->showManualRecommendation(' in m[2])
    manual_gate = countdown[2].index('->showManualRecommendation(')
    animator = countdown[2].index('Landroid/animation/ObjectAnimator;->ofInt(')
    assert manual_gate < animator
    assert '->dispose()V' in countdown[2][manual_gate:animator]
    assert 'return-void' in countdown[2][manual_gate:animator]
    # The view delegate's setVisibility(int) is R8-renamed (q in 31.6.0, p in 31.7.0).
    assert re.search(r'BaseViewDelegate;->\w+\(I\)V', countdown[2][manual_gate:animator])
    verify_countdown_control_flow(countdown)

    def quality_reader(m):
        return '"video_quality"' in m[2] and m[1].endswith('(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;')
    stock_quality, patched_quality = unique(stock, quality_reader), unique(patched, quality_reader)
    args = {'p1': '_live', 'p2': 'auto'}
    assert execute_reader(stock_quality, args, {}) == 'auto', 'stock quality negative control changed'
    for suffix in ['_live', '_vod', '_clip']:
        args = {'p1': suffix, 'p2': '480p' if suffix == '_clip' else 'auto'}
        assert execute_reader(patched_quality, args, {}) == ('source' if suffix == '_clip' else 'chunked')
        assert execute_reader(patched_quality, args, {}, quality=None) == args['p2']
        for saved in ['auto', '720p60', 'audio_only']:
            assert execute_reader(patched_quality, args, {'video_quality' + suffix: saved}) == saved
    for value, clip in CLIP_QUALITIES.items():
        assert execute_reader(patched_quality, {'p1': '_clip', 'p2': '480p'}, {}, quality=value) == clip
        assert execute_reader(patched_quality, {'p1': '_live', 'p2': 'auto'}, {}, quality=value) == value
    # The player's select-by-name resolves the request first, while the player register is loaded.
    selection = unique(patched, lambda m: PLAYBACK + '->playerQuality(' in m[2])
    body = [line.strip() for line in selection[2].splitlines()
            if line.strip() and not line.strip().startswith(('.', '#', ':'))]
    assert re.fullmatch(r'iget-object v0, p0, L[^;]+;->\w+:Lcom/amazonaws/ivs/player/MediaPlayer;', body[0]), body[0]
    assert body[1] == ('invoke-static {p1, v0}, ' + PLAYBACK +
                       '->playerQuality(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;'), body[1]
    assert body[2] == 'move-result-object p1', body[2]
    stock_body = [line.strip() for line in unique(stock, lambda m: m[:2] == selection[:2])[2].splitlines()
                  if line.strip() and not line.strip().startswith(('.', '#', ':'))]
    assert body[:1] + body[3:] == stock_body, 'player quality selection changed beyond the request'
    assert sum(PLAYBACK + '->qualityDefault(' in m[2] for m in patched) == 1, 'audio restore default'
    assert sum(PLAYBACK + '->qualityPreferenceDefault(' in m[2] for m in patched) == 1, 'content-type preference default'
    missing = unique(patched, lambda m: PLAYBACK + '->storageQualityDefault(' in m[2])
    assert '"catalystLocalStorage"' in missing[2]
    assert '->pushNull()V' not in missing[2]
    assert missing[2].count('->pushString(Ljava/lang/String;)V') == 4
    assert missing[2].count('->invoke([Ljava/lang/Object;)V') == unique(stock,
        lambda m: m[:2] == missing[:2])[2].count('->invoke([Ljava/lang/Object;)V'), 'callback paths must remain'
    shares = [m for m in patched if LINKS + '->putShareExtra(' in m[2]]
    assert len(shares) == 6
    assert sum(m[2].count(LINKS + '->putShareExtra(') for m in shares) == 9
    clips = [m for m in patched if LINKS + '->cleanClipboardText(' in m[2]]
    assert len(clips) == 3
    assert not any(PLAYBACK in m[2] or LINKS in m[2] for m in stock)
    print('PASS: stock negative controls, native saved/default/disabled choices, two feed autoplay paths, '
          'automatic recommendation gate/manual dispatch preservation/countdown cancellation, '
          'audio restore, RN missing rows/callback preservation, six share and three clipboard boundaries')

if __name__ == '__main__':
    main()
