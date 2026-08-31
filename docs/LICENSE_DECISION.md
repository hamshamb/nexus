# License Decision

| Field | Decision |
|---|---|
| License | Mozilla Public License 2.0 (`MPL-2.0`) |
| Copyright | © 2026 hamshamb |
| Decision date | 2026-08-31 |

## Rationale

- MPL-2.0 provides file-level copyleft: modifications to Nexus's covered files must be
  shared, while the mod can be combined with proprietary or differently licensed code
  at the module level. It preserves community improvements and leaves room for future
  integrations.
- Apache-2.0 dependencies such as Netty and Fabric can be used in an MPL-2.0 project.
- MIT and Apache-2.0 were simpler alternatives but provide no share-back requirement.
- LGPL and GPL options would have created friction with common JAR-in-JAR distribution
  and mixed-license modpacks in the Minecraft ecosystem.

## Consequences

- Source files carry the project's standard MPL-2.0 notice.
- The repository root [`LICENSE`](../LICENSE) contains the full license text.
- Dependencies must be compatible and recorded in
  [THIRD_PARTY.md](THIRD_PARTY.md).
- `kwik` was rejected partly because it is LGPL-3.0-only.

This is an engineering decision record, not legal advice. Professional legal review is
still required before commercial release.
