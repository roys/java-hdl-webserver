# Security policy

## Supported versions

Only the latest release gets security fixes. Update with `./update.sh`, or see
[Updating the server](README.md#-updating-the-server).

## Reporting a vulnerability

Please don't open a public issue. Report it privately on GitHub instead: on this repository's **Security** tab, choose
**Report a vulnerability**. Only the maintainer sees it.

Say what's affected, how to reproduce it, and what someone could do with it. You'll get an answer as soon as possible,
usually within a week. Busbridge is a one-person project, so a fix can take longer; you'll be told how it's going. Once
it's fixed and released, you'll be credited in the release notes, unless you'd rather not be.

## Scope

HDL Buspro itself has no security: anyone on the network HDL's IP module is on can control and reprogram the bus,
with or without Busbridge. That's HDL's design, not a vulnerability in Busbridge. The README's
[Security](README.md#-security) section says what Busbridge protects and what it doesn't.
