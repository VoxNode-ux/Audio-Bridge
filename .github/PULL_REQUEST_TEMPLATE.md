## Description

<!-- What does this PR change, and why? -->
Fixes the Bluetooth RFCOMM connection dropping when the receiver device screen locks mid-stream.

## Related issue

<!-- Closes #123, or "N/A" if there isn't one -->
Closes #42

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Documentation update
- [ ] CI / build configuration
- [ ] Dependency update
- [ ] Other (please describe):

## How was this tested?

<!-- e.g. "Ran ./gradlew lintDebug testDebugUnitTest locally", or
     "Tested manually on [device] as Sender / [device] as Receiver over
     [transport mode]" -->

If this touches networking, discovery, or transport-switching code, please
confirm which of these you actually tested on real devices (not just CI) —
these are areas that have broken silently before:

- [ ] Sender started before Receiver was ready (should retry/fail cleanly,
      not hang silently)
- [ ] Switched transport (e.g. Wi-Fi Direct → Hotspot) mid-session without
      needing to force-close the app
- [ ] N/A — this change doesn't touch networking/discovery/transport code

## Checklist

- [ ] I ran `./gradlew lintDebug testDebugUnitTest` locally and it passed
- [ ] I checked the Trivy security scan results (or Dependabot alerts) after
      this change, if it touches dependencies
- [ ] This PR does not introduce any proprietary dependencies, trackers, or
      analytics
- [ ] I've updated relevant documentation (README, comments) if needed
- [ ] My changes generate no new warnings

## Screenshots (if UI change)

<!-- Before/after screenshots, if applicable -->
N/A — this PR only touches connection/networking logic, no UI changes.
