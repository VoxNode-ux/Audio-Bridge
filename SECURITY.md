# Security Policy

## Supported Versions

AudioBridge is currently pre-1.0 / actively developed. Security fixes are
applied to the latest code on `main`. There is no long-term support for
older versions at this stage.

| Version | Supported          |
| ------- | ------------------ |
| latest (`main`) | :white_check_mark: |
| older commits   | :x:                |

## Reporting a Vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Instead, report it privately using one of these methods:

1. **GitHub Private Vulnerability Reporting** (preferred): go to the
   [Security tab](https://github.com/VoxNode-ux/Audio-Stream/security) of
   this repository and click **"Report a vulnerability"**.
2. If that's unavailable, open a regular issue with minimal detail (e.g.
   "Potential security issue — will follow up privately") and a maintainer
   will reach out for details through a private channel.

Please include:
- A description of the vulnerability and its potential impact
- Steps to reproduce (if applicable)
- Any relevant logs, screenshots, or proof-of-concept code

## What to expect

- Acknowledgement of your report as soon as reasonably possible.
- An assessment of the issue and, if confirmed, a fix will be prioritized.
- Credit in the fix's commit/release notes, if you'd like (or full anonymity,
  if you'd prefer).

## Scope

This project handles local network traffic (Wi-Fi hotspot, Wi-Fi Direct,
Bluetooth) for audio streaming between devices. Traffic never leaves the
local network or touches any third-party server. Relevant security concerns
include (but aren't limited to):
- Unauthorized device connection/pairing
- Data exposure over the local network
- Permission misuse
- Dependency vulnerabilities (also tracked automatically via Dependabot and
  Trivy security scanning in this repo's CI)

Thank you for helping keep AudioBridge and its users safe.
