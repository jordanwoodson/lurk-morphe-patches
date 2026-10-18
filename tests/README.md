# Tests

Run the APK-free host suite before pushing:

```sh
bin/test-host
```

It needs Python 3, Git, a JDK (21 recommended), Chrome or Chromium and the pinned Python dependency:

```sh
python3 -m pip install -r tests/requirements-host.txt
```

`JAVA_HOME` selects the JDK, `CHROME` selects the browser and `MORPHE_WORK`
selects the scratch directory (default `~/scratch/morphe`). It runs every registered check, reports
failures and exits nonzero if any check fails. Both PR builds and release jobs run it before building
or publishing. Add new standalone APK-free checks to the list in `bin/test-host`.

The host suite includes the Twitch interface, playback/link and privacy-consent JVM helpers, the
Kick player and privacy JVM helpers, the offline Kick chat-page browser test, and `test_release.py`.
CI installs the pinned dependency and verifies the hosted Chrome binary before running the suite;
missing dependencies fail the checks. The interface probe exercises startup
destinations, Home and profile tabs, explicit Clips routing, and disabled settings. Release tests use
temporary local Git repositories and in-memory publication state: they cover missing publication,
stable backmerge retries, advanced dev, checkout restoration, output preservation and host gate failure
propagation without calling GitHub or building a bundle. Scratch fixtures are removed on Linux and
retained on macOS.

The remaining checks need a patched APK, decoded output or a Hermes bundle, and for the probes an
Android emulator; those are separate from the host gate.

- `verify_*.py` and `test_*.py` inspect apktool output and Hermes bundles, or compile an
  extension helper against small stubs and run it on the JVM. Each script's docstring says
  what it takes.
- `*Probe.java` and `*Probe.kt` run real methods from the patched APK on ART. The
  `bin/test-<app>*` scripts compile them, push them to the emulator and run them.

See [docs/development.md](../docs/development.md#testing) for the workflow and the
per-app pages under `docs/` for what each check pins and why.
