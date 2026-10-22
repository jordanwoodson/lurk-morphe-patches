# Security policy

Lurk patches rewrite Twitch, Kick and Rumble on the user's phone, and some patches talk to
third-party services the user turns on (emote and badge providers, a playlist proxy the user
configures). A mistake here can leak data or weaken an app, so reports are welcome.

## What counts

- A patch that sends data somewhere it shouldn't, or sends more than its description says.
  The descriptions and [docs/](docs/README.md) state what each patch transmits.
- A patch that weakens the app: an exported component that wasn't, cleartext where there was
  TLS, a dropped signature or permission check, a relaxed network security config beyond what
  the patch documents.
- Anything that lets a stream, chat message, emote list or proxy response run code or crash
  the app in a way an attacker controls.
- The build and release pipeline: dependencies, the GitHub workflows, the published bundle.
- The workspace scripts under `bin/` handling an untrusted APK or bundle unsafely.

Ads still showing, a service detecting a modified client, or a feature not working are not
security issues; open a normal bug report for those.

## Reporting

Use GitHub's private reporting: **Security › Report a vulnerability** on
[the repository](https://github.com/jordanwoodson/lurk-morphe-patches/security/advisories/new).
Please don't open a public issue for something exploitable.

Include the app and version, the Lurk version, the patches involved and how to reproduce.
You'll get an acknowledgement within a week. Fixes ship as a new release; the advisory is
published once the release is out, with credit if you want it.

## Release integrity

Every release is built by the [Release workflow](.github/workflows/release.yml) on GitHub Actions
from its tagged commit, after the host checks pass. Each published `.mpp` carries a GitHub build
provenance attestation that ties the file to that workflow run and commit. To check a bundle you
downloaded:

```sh
gh attestation verify patches-1.12.0.mpp --repo jordanwoodson/lurk-morphe-patches
```

The bundle itself is **not signed yet**: `patches-bundle.json` leaves `signature_download_url`
empty, and Morphe Manager doesn't verify patch bundle signatures yet either. Manager installs the
`.mpp` that `patches-bundle.json` points to on this repository's GitHub releases, so the trust rests
on this repository and its release workflow; the attestation is the way to check a file
independently. The workflow's actions are pinned by commit and the Hermes compilers by checksum
(see [docs/development.md](docs/development.md#dependencies)).

Patched apps are signed by your own Morphe Manager with a key it creates, not by Lurk; see the
README on keeping that key.

## Supported versions

Only the latest release is supported. Each release targets one version of each app (listed in
the README); older app versions and older Lurk releases don't receive fixes.
