# 12. Releasing to Maven Central

Date: 2026-09-15

## Status

Accepted. Prepared, not yet exercised: no version has been published under this decision, and
the README's `Published` column stays `not yet` until one has.

## Context

Nine modules carry a `mavenPublishing {}` block -- Central coordinates, unconditional signing, a
real javadoc jar, one sources jar per module -- and nothing runs them. The README lists every
module as `not yet`, and a library nobody can depend on by coordinate is a repository, not a
library.

Publishing to Central is a one-way door. A version is never re-uploaded and never deleted, so a
publication that is wrong -- a variant missing, an `.asc` absent, a POM field the portal rejects
-- is not fixed, it is abandoned and the number is burnt. The producer's own build cannot see the
failure that matters most: a consumer resolving the published metadata rather than a project
dependency, on every target the module publishes. Everything in this repository's tests runs
against project dependencies, which never read a `.module` file.

`uny/a2ui-compose` -- the renderer `agui-a2ui` depends on -- crossed this door on 2026-09-08 with
a release path built around exactly that gap: a tag is the version, a separate consumer build
resolves the publish by coordinate before anything is uploaded, an on-demand rehearsal runs the
same path against a throwaway repository and a throwaway key, and the upload stops short of
release so a human presses the last button. That path is one decision away from applying here,
and the decision is what changes.

### What is not the same

This repository publishes two target lists, not one. `agui-model`, `agui-core` and `agui-agent`
publish the five targets the upstream SDK does, `iosX64` among them (decision 1). The other six
ride on Compose Multiplatform or on `a2ui-compose`, neither of which publishes `iosX64`, so they
publish four. A consumer build with one `commonMain` naming all nine cannot resolve on `iosX64`;
one that drops `iosX64` leaves three modules' published variant uncovered.

And `agui-a2ui` is in the second list, which was not obvious from reading it: the module carries
no Compose, and the first draft of the consumer build put it with the first three. The gate
refused to resolve it on `iosX64` -- its `.module` lists no such variant, because `a2ui-core`'s
does not. That is the first thing the gate found, before any release ran.

## Decision

**The release path is `uny/a2ui-compose`'s, copied, with one addition.** Three files carry it:

- `.github/workflows/cd.yml` runs on a `v*` tag and nowhere else. The tag is the version; a
  `release` environment with a required reviewer holds the secrets back until a human approves
  the run against the tagged commit; the full suite runs; every module publishes locally and is
  signed there, at the release version, so a missing or malformed key fails before the upload;
  the consumer build resolves that publish on every target; and `publishToMavenCentral` uploads
  and validates without releasing. The last step is a button in the Central Portal, held by the
  account that owns the namespace.
- `.github/workflows/release-dry-run.yml` runs the same path on demand with the upload replaced
  by a publish to a directory under the runner's temp and the release key replaced by one
  generated inside the job -- passphrase-protected and passed by key id, because that is the
  shape `cd.yml` signs in. It reads no secret and can write to nothing outside the runner. A
  green run is "the build is ready to try", not "the release will succeed": Central's own
  validation only runs on a real upload.
- `smoke-test/` is the consumer build both workflows run. It is a separate Gradle build with its
  own `settings.gradle.kts`, resolving `dev.ynagai.agui` from `mavenLocal()` and nowhere else,
  and compiling against one public symbol from each of the nine modules on every target each
  publishes.

**The addition is a second shared source set.** `commonMain` depends on the three five-target
modules; `noIosX64Main`, a group added to the default hierarchy template covering every target
but `iosX64`, depends on the six four-target ones. Each holds one file. Both workflows compile
both metadata compilations by name, because `compileKotlinMetadata` -- the task a reader would
reach for -- is disabled under the hierarchical model and compiles nothing.

**The first version is `0.1.0`.** `VERSION_NAME` in `gradle.properties` stays `0.1.0-SNAPSHOT`;
the tag `v0.1.0` is what decides the released version, and nothing in the tree has to change to
cut it.

**What is not copied.** `a2ui-compose`'s workflows run under JDK 17 and check `checkLegacyAbi`;
these run under JDK 21 (the floor upstream's class-file 65 bytecode sets, as `agui-agent`'s build
script records) and `checkKotlinAbi`. The
consumer task list names this repository's targets and no web or macOS backend. Measurements the
copied comments cite about the key id's form and a wrong passphrase were made in `a2ui-compose`,
on the same plugin version and the same `mavenPublishing` shape, and the comments say so. What
was measured here, on 2026-09-15: the consumer build resolves a `0.1.0-probe-SNAPSHOT` publish
on all seven compilations, fails when one platform variant is removed, and fails when only the
metadata jar is (`smoke-test/README.md`); a `0.1.0-probe` publish with no key fails at the first
`sign*Publication` task with `No configured signatory`; and the same publish with a throwaway key
passed as `cd.yml` passes it lands 48 POMs with an `.asc` beside every file.

## Consequences

- Nothing is published by this decision. What exists after it is a path that has been rehearsed
  locally up to the point where a key or a portal is needed, and two workflows that have never
  run. The `release` environment, its required reviewer and its five secrets
  (`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `GPG_KEY_ID` in the short 8-hex form,
  `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`) are repository settings, made by the account that owns
  them, and until they exist `cd.yml` names an environment that gates nothing.
- The order of operations for the first release is: run `release-dry-run.yml` once from the
  Actions tab and read its summary; create the environment and secrets; push `v0.1.0`; approve
  the run; press the button in the portal; then change the README's `Published` column and the
  status line, in a PR, after the coordinates resolve from Central.
- A new target on any module is not covered by the gate until `smoke-test/build.gradle.kts` and
  both workflows' task lists name it. Removing one fails loudly; adding one is silent. That
  asymmetry is inherited and recorded, not fixed.
- `smoke-test/` is a second Gradle build in the repository, invisible to `./gradlew build` and to
  CI's `build.yml`. It compiles only when a workflow or a person runs it against a publish, and
  it needs an Android SDK and macOS to compile every target -- the same conditions as the
  producer's own Apple targets, for the same reason.

## What would change the answer

- `a2ui-compose` publishing `iosX64`, or Compose Multiplatform doing so. Six modules would then
  publish five targets and the second source set would have nothing to hold; `commonMain` would
  take all nine and `noIosX64Main` would go.
- `publishAndReleaseToMavenCentral` replacing the portal button, once a release has gone through
  by hand and the path is trusted. `cd.yml` records why it stops at the upload today.
- A module that must not publish -- `agui-sample` and `agui-replay` today -- gaining a
  `mavenPublishing` block by accident. The gate would not notice: it enumerates the modules it
  expects, not the ones the publish wrote. `git grep mavenPublishing` before a tag is the check.
