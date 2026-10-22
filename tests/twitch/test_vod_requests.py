#!/usr/bin/env python3
"""Chapter request lifecycle against actual TwitchVod with controllable platform HTTPS/UI boundaries."""
import sys
from pathlib import Path
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'shared'))
from host_java import run, PACKAGE
from vod_platform import VOD
if __name__ == '__main__':
    helpers=['twitch/TwitchVod.java','twitch/TwitchRequest.java']
    run('TwitchVodRequestsProbe',helpers,Path(__file__).with_name('TwitchVodRequestsProbe.java').read_text(),
        sys.argv[1:] or ['queued-player-detach','shared-player-detach','replaced-player-detach','failure-retention','waiters','queued-disable','queued-navigation','shared','retry','eviction','queue','navigate','detach','disabled','ownership'],extra=VOD)
