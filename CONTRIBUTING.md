# Contributing to AudioBridge

Thanks for your interest in contributing! This project is a solo-maintained,
FOSS Android app, and contributions of all sizes are welcome — from typo
fixes to new features.

## Before you start

- Check existing [issues](https://github.com/VoxNode-ux/Audio-Stream/issues)
  and [pull requests](https://github.com/VoxNode-ux/Audio-Stream/pulls) to
  avoid duplicate work.
- For anything larger than a small fix, open an issue first to discuss the
  approach before writing code — this saves everyone time if the direction
  needs adjusting.

## Hard requirements

- **No proprietary dependencies, trackers, or analytics.** This is a FOSS
  project by design (Apache 2.0, aiming for F-Droid distribution). PRs that
  introduce any of these will not be accepted, regardless of the feature they
  enable.
- All code must pass CI (`lintDebug`, `testDebugUnitTest`, `assembleDebug`)
  before merge.

## Development setup

Requires JDK 21 and the Android SDK (`compileSdk 37`, `minSdk 29`).

```bash
git clone https://github.com/VoxNode-ux/Audio-Stream.git
cd Audio-Stream
./gradlew assembleDebug
```

Run lint and tests locally before opening a PR, same as CI:

```bash
./gradlew lintDebug testDebugUnitTest
```

## Making changes

1. Fork the repo and create a branch from `main`:
   `git checkout -b my-feature`
2. Make your changes, following the existing code style (see
   `app/src/main/java/com/audiobridge/app/` for examples — Kotlin,
   Jetpack Compose, one `AudioTransport` implementation per transport).
3. Commit with a clear message describing *what* changed and *why*.
4. Push your branch and open a Pull Request against `main`.
5. Fill out the PR template — it's short, but the CI results and a brief
   description of your testing help review go faster.

## Code style

- Kotlin idioms over Java-style verbosity where reasonable.
- Doc comments (`/** ... */`) on non-obvious classes/functions, especially
  anything explaining *why* a workaround exists (see existing files for the
  pattern — plenty of Android platform quirks are documented inline).
- Keep transport implementations (`UdpStreamer`, `TcpStreamer`,
  `BluetoothTransport`) consistent with the shared `AudioTransport` interface.

## Reporting bugs

Use the bug report issue template. Include:
- Android version and device model (sender and receiver)
- Which transport mode (Hotspot/Wi-Fi, Wi-Fi Direct, Bluetooth)
- Steps to reproduce
- What you expected vs. what happened

## Security issues

Please **do not** open a public issue for security vulnerabilities — see
[SECURITY.md](SECURITY.md) instead.

## Questions

Open a [discussion](https://github.com/VoxNode-ux/Audio-Stream/discussions)
or an issue tagged `question`.
