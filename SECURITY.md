# Security Policy

Nexus handles network connections and short-lived session credentials. Please report
security problems privately so users are not exposed before a fix is available.

## Supported versions

Nexus does not have a production-ready release yet. Security fixes are made on the
latest `main` branch; old development snapshots are not supported.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting feature on this repository. Include:

- the affected commit or version;
- the environment and configuration used;
- clear reproduction steps or a minimal proof of concept;
- the expected and observed impact; and
- any suggested mitigation, if known.

Do not open a public issue for an unpatched vulnerability. Do not include live API
keys, Microsoft/Mojang credentials, personal account data, or reusable session tokens
in a report. Replace secrets with unmistakable placeholders and revoke anything that
may have been exposed.

If private vulnerability reporting is unavailable, open a public issue containing no
exploit details or secrets and ask the maintainer to establish a private channel.

## Scope

Particularly useful reports include authentication bypasses, admission replay, secret
exposure, unsafe route handling, denial-of-service weaknesses, lifecycle leaks, and
ways to make the mod connect to an unintended address.

The project's current security boundaries and known verification gaps are documented
in [Architecture](docs/ARCHITECTURE.md) and
[Implementation status](docs/IMPLEMENTATION_STATUS.md).
