# Security policy

## Supported versions

Fixes only go into new releases, not into older versions. Update with `./update.sh`, or see
[Updating the server](README.md#-updating-the-server).

## Reporting a vulnerability

Please don't open a public issue. Report it privately on GitHub instead: on this repository's **Security** tab, choose
**Report a vulnerability**. Only the maintainer sees it.

Say what's affected, how to reproduce it, and what someone could do with it. Busbridge is a free project made by one
person in spare time, so there's no set time for an answer or a fix. If it's fixed, you can be credited in the release
notes, if you'd like.

## Scope

HDL Buspro itself has no security: anyone on the network HDL's IP module is on can control and reprogram the bus,
with or without Busbridge. That's HDL's design, not a vulnerability in Busbridge. The README's
[Security](README.md#-security) section says what Busbridge protects and what it doesn't.
