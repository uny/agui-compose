# Consumer smoke test

A separate Gradle build that depends on `dev.ynagai.agui` **by coordinate**, from a repository,
the way anyone else would.

## Why it exists

Publishing a Kotlin Multiplatform library has a failure mode that no amount of green in the
producer's own build will show: **the publish succeeds and the consumer cannot resolve.** The
producer's tests run against project dependencies, which resolve through its own configurations
and never read the `.module` metadata, the POM, or the per-target coordinates. A variant that was
published wrong, or not published at all, is invisible until someone else tries to use the release.

Maven Central makes that expensive. A version is never re-uploaded and never deleted, so a broken
publication is not fixed -- it is abandoned, and the number is burnt.

So this build resolves the artifacts from a repository and compiles against a symbol from each of
the nine modules, on every target each module publishes. Resolution alone would not be enough: an
artifact can resolve and still be missing the class, because a `.module` file can point a variant
at a jar that does not carry it.

## Two source sets, because two target lists

`agui-model`, `agui-core` and `agui-agent` publish the five targets the upstream SDK does,
`iosX64` among them. The other six ride on Compose Multiplatform or on `a2ui-compose`, neither of
which publishes `iosX64`, so they publish four. One `commonMain` naming all nine would fail to
resolve on `iosX64`; dropping `iosX64` from this build would leave a published variant of three
modules uncovered. So `commonMain` depends on the three and `noIosX64Main` -- a group added to the
default hierarchy template in `build.gradle.kts` -- depends on the six, and each source set holds
one file touching one symbol per module.

The first draft put `agui-a2ui` in the first group, on the grounds that it carries no Compose. The
gate said otherwise: its `.module` lists no `iosX64` variant, because `a2ui-core`'s does not. That
is the kind of fact this build exists to surface.

## And a second consumer, for the `compileSdk` floor

`docs/decisions/0014` promises that `agui-model`, `agui-core`, `agui-agent` and `agui-a2ui` can be
taken by an Android consumer compiling against API 24, because none of them draws and every AAR
they depend on asks for 24 or less. The root project cannot check that: its Android target is at
the drawing floor, 37, so it resolves those four at 37 and says nothing about the promise.

`floor/` is that check -- an Android consumer at 24 depending on exactly those four. A second
Gradle project rather than a second source set, because one Android library cannot be built at two
API levels. What enforces the floor is AGP's own `checkAarMetadata`, which `assemble` runs: it
reads each dependency's published `aar-metadata.properties` and fails naming the module and the
version it wants, which is the same failure a consumer gets, from the same file, at the same API
level. So a dependency bump that raises one of the four, or a module reading the wrong catalog key,
fails on the tag rather than in someone else's project.

It reads the four coordinates and `compileSdk` from the same places the root project and the
producer's catalog hold them, not from literals, so neither can drift out from under it.

## Running it by hand

From the repository root, with its wrapper -- this build has none of its own, because a second
copy of `gradle-wrapper.jar` is a second thing to keep pinned:

```bash
./gradlew publishToMavenLocal
./gradlew -p smoke-test \
  compileCommonMainKotlinMetadata compileNoIosX64MainKotlinMetadata compileKotlinJvm \
  compileKotlinIosArm64 compileKotlinIosSimulatorArm64 compileKotlinIosX64 \
  compileAndroidMain :floor:assemble
```

Needs an Android SDK (`ANDROID_HOME`, or `sdk.dir` in `smoke-test/local.properties` -- the root
`local.properties` is not read across the build boundary) and, for the three Apple targets, macOS.
On Linux those three compilations are skipped rather than failed
(`kotlin.native.ignoreDisabledTargets=true` in `gradle.properties`, as in the producer), so a green
run there says nothing about the Apple variants -- which is why both workflows run on macOS.

`-PaguiVersion=<version>` selects what to resolve; with no property it reads `VERSION_NAME` from
the producer's `../gradle.properties`, so it cannot go on naming a version the producer has left
behind.

`compileCommonMainKotlinMetadata` and `compileNoIosX64MainKotlinMetadata`, not
`compileKotlinMetadata`: the latter is a task that exists but is disabled under the hierarchical
source-set model, so naming it compiles nothing. See the second control below.

## What it does not check

Compile classpaths only, on a build with no tests and no `run`. So it does **not** see a defect
confined to a *runtime* variant -- `agui-agent`'s `kotlinx-datetime` pin (decision 0007) is
`implementation`, so it reaches a consumer's runtime classpath and not its compile classpath, and
dropping it would pass this gate on JVM and Android and fail first in a consumer's
`NoClassDefFoundError`, exactly as it did before the pin existed.

It also does not pin each module's `api` scopes individually. It depends on all nine, so a type
reachable through more than one of them stays reachable when one downgrades it. And the module and
target lists are enumerated by hand in this build: *removing* a published target fails loudly, but
*adding* one is simply not covered until someone adds it here and to both workflows' task lists.

## Where it runs

`cd.yml`, between `publishToMavenLocal` and the upload to Central -- so a publication that a
consumer cannot resolve fails the release before anything reaches the portal.

`release-dry-run.yml` runs the same pair on demand, against a repository under the runner's temp
rather than `~/.m2`, signed with a key generated in the job. That is where the release path gets
exercised before a tag exists -- `cd.yml` itself cannot run until one does, and Central neither
re-uploads nor deletes, so the first release is a poor place for a step's first execution.

Both point the consumer at the repository the publish just wrote, but by different means. `cd.yml`
passes no `-Dmaven.repo.local`: publish and consumer both fall through to the default `~/.m2`, and
what a fresh GitHub-hosted runner adds is that the repository holds *nothing else*. On a warm one
a stale version left from an earlier run could answer for a variant this publish failed to write.
`release-dry-run.yml` names a directory under the runner's temp instead, which nothing else can
have written to, so it resolves what *that run* published wherever it runs. Either way
`settings.gradle.kts` binds `dev.ynagai.agui` to `mavenLocal()` exclusively, so nothing else can
answer for the group under test -- while `dev.ynagai.a2ui`, which `agui-a2ui` depends on and which
is already on Central, still comes from Central, as it would for a consumer.

## Checking that it still bites

A guard that cannot fail is not a guard. Two controls, both measured on 2026-09-15 against a
`0.1.0-probe-SNAPSHOT` publish into a scratch `-Dmaven.repo.local`; the commands below say `~/.m2`
for the by-hand case.

**One published platform variant removed** -- the per-target half:

```bash
mv ~/.m2/repository/dev/ynagai/agui/agui-core-iosx64 /tmp/
./gradlew -p smoke-test compileKotlinIosX64 --rerun-tasks      # must fail to resolve
mv /tmp/agui-core-iosx64 ~/.m2/repository/dev/ynagai/agui/
```

`Could not find dev.ynagai.agui:agui-core-iosx64:<version>`. This keeps working after a version
is on Central because `dev.ynagai.agui` is bound to `mavenLocal()` by `exclusiveContent` and is
not looked up anywhere else -- otherwise the fallback would answer and the control would stop
biting.

**Only the metadata variant broken**, every platform variant left intact -- the half a
per-target-only gate cannot see, and the reason the task names above are what they are:

```bash
mv ~/.m2/repository/dev/ynagai/agui/agui-core/<version>/agui-core-<version>.jar /tmp/
./gradlew -p smoke-test compileCommonMainKotlinMetadata --rerun-tasks   # must fail
./gradlew -p smoke-test compileKotlinMetadata --rerun-tasks             # the old name: SKIPPED, green
mv /tmp/agui-core-<version>.jar ~/.m2/repository/dev/ynagai/agui/agui-core/<version>/
```

`compileCommonMainKotlinMetadata` fails in `transformCommonMainDependenciesMetadata` with
`Could not find dev.ynagai.agui:agui-core:<version>`; `compileKotlinMetadata` reports `SKIPPED`
and exits 0. A consumer writing `commonMain` against that publication could not have compiled,
and a gate naming the old task would have passed it.

**A module that draws added to the floor consumer** -- the third control, measured on 2026-09-25
against a `0.2.0-floorprobe-SNAPSHOT` publish. Add a drawing module to `floor/build.gradle.kts`'s
dependencies and the gate must refuse it:

```bash
# with implementation("dev.ynagai.agui:agui-compose:$aguiVersion") added to floor/
./gradlew -p smoke-test :floor:assemble --rerun-tasks     # must fail
```

`:floor:checkAndroidMainAarMetadata` fails with `53 issues were found when checking AAR metadata`,
each naming a dependency that `requires libraries and applications that depend on it to compile
against version 37 or later of the Android APIs` and stating `:floor is currently compiled against
android-24`. Which is what a consumer at 24 would see, and why the four modules' floor is a
checked claim rather than a stated one.
