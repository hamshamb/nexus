# Clean-Room Provenance Rules

Nexus is an independently engineered Minecraft world-hosting mod. These rules exist so
that its independence is demonstrable, not merely asserted. They bind every contributor
and every automated tool working on this repository.

## The short version

Build from public standards, official platform behavior, permissively licensed
dependencies, and original experiments. Do not inspect or reproduce restricted
competitors' implementation details. When unsure whether a source is allowed, stop and
record the question before using it.

## Prohibited source material

**Essential Mod** is source-available under a restrictive license and is prohibited
reference material in every form:

- Do not open, clone, read, decompile, or search its source or binaries.
- Do not copy or translate its code, assets, icons, UI layouts, strings, packet
  structures, protocols, backend APIs, or database structures.
- Do not derive design decisions from descriptions or summaries of its internals,
  including AI-generated summaries and second-hand writeups of its architecture.
- Do not ask another person or agent to do any of the above on your behalf.

If an Essential repository or artifact appears anywhere in or near the workspace, do not
open it. If search results expose Essential implementation details, do not incorporate
them.

This prohibition covers *implementation details*. Publicly observable product behavior
(e.g. "hosting mods exist and use invite flows") is common knowledge and is not a
protected detail.

## Permitted sources

- Minecraft Java Edition's own behavior and its unobfuscated code as shipped by Mojang
  (26.1+), inspected for interoperability with the game we are extending.
- Fabric and NeoForge documentation and Apache-2.0 source.
- Java/JVM documentation; Netty documentation.
- IETF RFCs and standards: ICE, STUN, TURN, QUIC, TLS.
- Public NAT-traversal research and public Minecraft protocol documentation.
- Open-source libraries after an independent license review (see below).
- Ordinary software-engineering knowledge and original experimentation against our own
  implementation.

## Dependency review rules

Before adding any dependency:

1. Read its actual license from its POM or repository — never assume a license from the
   vendor, ecosystem, or a sibling project.
2. Record it in `docs/THIRD_PARTY.md` with exact coordinates, version, SPDX license id,
   and why we use it.
3. Prefer maintained, boring libraries. Never vendor code without carrying its license.

## Provenance expectations

- Design decisions of consequence are recorded in `docs/ARCHITECTURE.md` with their
  reasoning, so the origin of each idea is traceable to this repository's own analysis.
- Facts about Minecraft internals are established by disassembling the unobfuscated
  Mojang-published jar for the targeted version and are cited as such.
- Pull requests identify any new external technical source that materially influenced
  the design.
- AI tools may help with work only when their prompts and supplied context follow these
  same restrictions. Generated output does not make a prohibited source permissible.

## Contributor checklist

Before submitting a change, confirm that:

- no prohibited source, binary, screenshot, summary, or derivative was consulted;
- every added asset and dependency has a known, compatible license;
- protocol and interoperability claims can be traced to a permitted source or an
  experiment against Nexus itself; and
- relevant design and dependency records were updated.

This is an engineering provenance record, not legal advice. Professional legal review
is still required before commercial release.
