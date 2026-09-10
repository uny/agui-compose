# 4. Parsing Markdown without a design system

Date: 2026-09-10

## Status

Accepted.

## Context

[Decision 2](0002-what-the-ui-layer-does-not-depend-on.md) left `agui-compose` with no Markdown
parser and a slot -- `AguiTextRenderer` -- where one is fitted. [Decision
3](0003-what-material-3-decides-for-you.md) kept that slot alive one layer up: `agui-material3`
draws its prose *through* `LocalAguiTextRenderer` rather than calling `Text` itself, which is what
makes a parser fittable underneath a design system rather than instead of one. This module is the
parser, and it is a separate one for the reason decision 3 recorded: a consumer who wants Material
3 should not receive a Markdown grammar with it.

That leaves the question this record exists for. `agui-markdown` may not depend on `agui-material3`
-- but a Markdown renderer needs colours and a type scale, and the module has no design system to
read them from.

### Which artifact

`com.mikepenz:multiplatform-markdown-renderer` publishes the repository's four UI targets exactly
(`android`, `jvm`, `ios_arm64`, `ios_simulator_arm64`, plus `js`, `wasmJs` and `macosArm64` this
repository does not take), and 0.42.0 added an incremental parser. It ships as a plain artifact and
as `-m3`.

`-m3` was rejected. Its entire content is four files' worth of `markdownColor()` and
`markdownTypography()` reading `MaterialTheme` -- which is the design system this module is
separate in order to avoid. Worth recording because the shape of the dependency misleads: `-m3`
declares every Compose dependency `compileOnly`, so `material3` appears in neither artifact's Gradle
metadata and does not arrive transitively at all. A module built on `-m3` has to declare
`material3` itself, in `api`, to link. The choice is not "does material3 come along" but "does this
module take a design system", and the answer is the same one decision 3 gave.

### Where the styling comes from

Three shapes were available for a renderer that cannot read a theme.

Reading an ambient style is not reachable, for the reason decision 3 already established: foundation
has no ambient text style or content colour, so below Material 3 there is nothing to read.

Hard-coding a palette would make the module's output impossible to theme -- the failure decision 3
called *looking like a bug in the design system*, moved one layer down and made permanent.

What is left is taking the values as constructor parameters. The wrinkle is that the values a
Material 3 consumer wants are composition reads, and a renderer is constructed outside composition:
`LocalAguiTextRenderer` is a static composition local with no equality beyond identity, so a
renderer rebuilt to pick up a theme change re-parses every visible run on every frame.

### What `streaming` is for

`AguiTextRenderer.Render` takes a `streaming` flag, and a Markdown implementation that ignored it
would leave this module hard to justify as a separate one. The parser's incremental mode is
append-only: it takes chunks, keeps the settled prefix of the document parsed, and re-parses only
the tail. The renderer is handed the accumulated run instead, so the chunk has to be recovered by
comparing what has been fed in with what has now arrived.

## Decision

**`agui-markdown` is a fifth module depending on `agui-compose` and the plain
`multiplatform-markdown-renderer`, and on no design system.** It publishes one `AguiTextRenderer`
implementation and two factories that build the parser's `MarkdownColors` and `MarkdownTypography`
from a single colour and a single `TextStyle`.

**The style parameters are `@Composable` lambdas rather than values.** A caller writes
`remember { MarkdownAguiTextRenderer(colors = { markdownAguiColors(text = LocalContentColor.current) }, ...) }`
once, with no keys: the reads happen inside composition, so a theme change reaches the renderer
without the renderer's identity changing.

**The default is black text at `TextStyle.Default`,** matching `PlainAguiTextRenderer` rather than
guessing at a palette. Under Material 3 the two lambdas above are required, and the KDoc says so in
those words.

**`streaming` selects the parser.** While `true`, the incremental parser is fed the difference
between the run so far and what it has already seen; when it flips to `false`, the same text is
parsed complete, synchronously, so the finished answer does not blink out for a frame while an
asynchronous parse lands.

**The unsettled tail is drawn while it streams,** rather than held back until the syntax that could
still change its meaning has arrived. Holding it back was the shape originally imagined for this
flag -- do not show a half-typed fence -- and it is the wrong one: the tail of a response being
typed is usually a sentence, not a fence, so suppressing it would leave every paragraph invisible
until the blank line that ends it. What the flag buys is the incremental parse, not suppression.

## Consequences

The two-lambda requirement is a real edge. A renderer constructed with defaults and provided inside
`ProvideMaterial3Agui` draws black text in a dark theme and ignores the type scale -- the bug decision
3 found and fixed for `agui-material3`'s own renderer, now reachable again through a different door.
It is documented rather than designed away, because designing it away means either a design system
dependency or a hard-coded palette, and both were rejected above. A `agui-markdown-material3`
bridge -- two functions, no new grammar -- would close it, and is deliberately not written yet: one
module is not evidence of a pattern.

**Parsing agent text makes it clickable, and that is a security consequence rather than a visual
one.** Before this module the same string was drawn as characters; now a URL in it is a link
carrying the agent's own destination, with no scheme filtering in this module or in the parser, and
GFM autolinks bare URLs so no `[](...)` syntax is needed. A tap reaches the ambient
`LocalUriHandler`, which on every platform will happily open an application's own deep-link scheme
— so an application with authenticated deep links should provide a handler that decides what it
acts on. This is recorded rather than filtered here because a scheme allow-list belongs to the
application that owns the schemes, and a library-level one is either too narrow to be safe or too
broad to be worth having. Images are not the same story: the parser's default image transformer is
a no-op, so nothing here reaches the network by itself.

Feeding an append-only parser from an accumulated string means noticing when the run is not an
extension of itself -- a regenerated message, a renderer reused across two runs -- and starting the
parser over. The parser and everything drawn from it therefore share one `key`, because replacing
only the parser leaves `Markdown` holding the previous snapshot's syntax tree while reading text out
of the new parser, and indexing an old document's node ranges into an empty string crashes. That was
found by a test rather than by reading, and the test is kept.

This is the third module whose Android target compiles and runs no tests: everything here draws, so
everything lives in `composeUiTest`, which Android does not depend on. Decision 3 recorded the hole
and the two ways out of it; nothing here changes that argument, and it is not re-litigated per
module.

The published surface is one class and two functions. Swapping the parser later is a change to this
module's internals plus whichever of `MarkdownColors` and `MarkdownTypography` a caller named --
both of which are the parser's types, so the swap is not free. That is the price of letting a caller
style the output at all, and it is paid once here rather than by every consumer.

## What would change the answer

An ambient text style below Material 3 -- foundation gaining one, or this repository defining one in
`agui-compose` -- would remove the two-lambda requirement and with it the sharpest consequence
above. Defining one is a real option and is deliberately not taken here, because it is a decision
about `agui-compose`'s surface and would be made for reasons larger than Markdown.

A second module wanting the same Material 3 bridge would make `agui-markdown-material3` worth
writing rather than premature.

If the upstream protocol ever states a syntax for `TEXT_MESSAGE_CONTENT`, the case for this being an
optional module rather than a default weakens considerably -- though the case for it being a
separate artifact from a design system does not.
