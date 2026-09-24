# 14. Letting Compose Multiplatform set both floors

Date: 2026-09-25

## Status

Accepted. Settles [#20](https://github.com/uny/agui-compose/issues/20) and supersedes the reasoning
recorded in [#19](https://github.com/uny/agui-compose/issues/19) for the `compileSdk` split, whose
outcome it keeps.

## Context

Two floors are published from this tree and neither was chosen: the Kotlin version, which a
consumer cannot scope to one module because the compiler plugin is project-wide, and the
`compileSdk` a module's AAR metadata demands. `0.1.0` shipped both at whatever the newest
dependency asked for — Kotlin 2.4.10, `compileSdk` 37 for all nine modules.

Three sibling repositories arrived at three different answers to the same question within a week.
`uny/autograph` lowered to Kotlin 2.3 and held `compileSdk` at 35, arguing that no production
source needs 2.4. `uny/a2ui-compose` closed its equivalent issue at 2.4, arguing that nothing
blocks staying current. This repository had a locked decision for 2.3.21 made on a premise that
turned out to be false. Each position named a value; none named a condition for changing it.

### The premise that did not hold

All three repositories' issues rested on "KSP has no Kotlin 2.4 line, so every consumer using Room,
Dagger or Moshi is locked out". That conflates two versions. KSP's own `2.3.x` numbering is the
Kotlin version KSP is *built with*, which since KSP2 is decoupled from the consuming project's —
google/ksp#2964 is an existence proof, a report of KSP 2.3.9 running on a Kotlin 2.4.0 project. The
correction is recorded on uny/a2ui-compose#64 and uny/autograph#205.

What is left of the constraint is narrower on both axes. Only klib targets refuse a newer ABI:
Kotlin 2.3's `KotlinAbiVersion.isCompatibleTo` is `isAtMost(CURRENT)`, so an `abi_version = 2.4.0`
klib is rejected on iOS, while JVM and Android class-file metadata is read one language release
ahead. And the consumers actually pinned to 2.3 are those held there by a *compiler* plugin with no
2.4 release — SKIE, Realm-Kotlin, Mokkery, Ktorfit, Poko — or by upgrade policy. No such consumer
of this library is known.

### What a value without a condition costs

Holding a floor with no trigger to move is the failure uny/autograph#224 documents on its own
`compileSdk` axis: holding at 35 meant holding Compose Multiplatform at 1.11.1, which has no patch
line, so the hold was not "protect a consumer" but "never take 1.12". Tracking the newest of
everything is the mirror image — it has no floor at all, and each dependency bump re-opens the
question from scratch. Both failures are the absence of a rule, not the wrong number.

## Decision

**Both floors follow Compose Multiplatform, and neither moves on its own schedule.**

- **The Kotlin floor is the line the current stable Compose Multiplatform is built on**, read from
  its klib manifests. At 1.12.0 that is `compiler_version = 2.3.20`, so this repository builds on
  **2.3.21**.
- **A drawing module's `compileSdk` floor is the `minCompileSdk` the current stable Compose
  declares** — 37 at 1.12.0. **A module that draws nothing takes what its own dependencies
  declare**, which is `minSdk`: 24 for `agui-model`, `agui-core`, `agui-agent` and `agui-a2ui`.

Both directions are observable, which is what makes this a rule rather than a preference. *Below*
Compose's line buys nobody, because a consumer of Compose is already at or above it. *Above* it
locks out consumers Compose itself admits — and raising a floor is the one-way direction, while
lowering one costs consumers nothing.

**The trigger is a Compose Multiplatform stable release that moves either value.** A Kotlin release
on its own is not a reason to move; a dependabot bump that would raise a floor is declined on this
rule rather than re-argued. When Compose moves, the Kotlin bump and the
`abiValidation { enabled.set(true) }` → `abiValidation()` migration go in the same change, because
the two DSL forms are mutually exclusive across the 2.3/2.4 boundary.

**The same rule is recorded in both sibling repositories** (uny/autograph#224,
uny/a2ui-compose#64), so the three move together rather than diverging again.

## Consequences

`0.2.0` lowers both floors, which is the two-way direction: a `0.1.0` consumer on Kotlin 2.4 and
`compileSdk` 37 is unaffected, and one on 2.3 with an iOS target can now take the library at all.
The release notes say so rather than leaving it to be discovered.

`agui-a2ui` joins the three non-drawing modules at 24. That was not available when
[#19](https://github.com/uny/agui-compose/issues/19) split the floor: `a2ui-core` 0.1.0's AAR asked
for 37 because that repository published one shared value. Its 0.2.0 lowered it
(uny/a2ui-compose#88), which is the only reason this row changed.

Two constants moved out of a `private companion object` in `UiTranscriptReducer`. A `const val`
there compiles to a public static field on the enclosing class, so `THINKING_ID_PREFIX` and
`ENCRYPTED_SUBTYPE_TOOL_CALL` were on the published JVM surface; the ABI dump only revealed it when
the default module name changed with the Kotlin version. The leak predates this change and is
fixed by it.

The Compose lambda-mangling suffix in `agui-compose` and `agui-material3`'s dumps changed from
`$dev_ynagai_agui_agui_compose` to `$agui_compose`, because Kotlin 2.4 derives a module's default
name as `group:name` and 2.3 does not. Those are synthetic accessors on internal singletons rather
than API anyone names, so the dumps are updated and nothing else follows.

The recurring cost is one decision per Kotlin bump PR — declined on the rule — and a per-case call
whenever a dependency moves to a 2.4 build before Compose does. That case has happened once:
[decision 13](0013-owning-the-markdown-rendering-layer.md), and it cost a day.

## What would change the answer

A Compose Multiplatform stable built on Kotlin 2.4 moves the Kotlin floor, by the rule rather than
by a new decision.

A concrete consumer with a klib target held on 2.3 by a compiler plugin would make the floor
load-bearing rather than merely cheap. None is known, and the rule does not depend on one turning
up — but it would change what a future decision to move is weighed against.

Compose Multiplatform ceasing to be the dependency that sets both values — this repository taking
something with a higher `minCompileSdk` or a newer build, for a reason worth the floor — would make
the anchor wrong. The rule would then name that dependency instead, not disappear.
