# 13. Owning the Markdown rendering layer

Date: 2026-09-18

## Status

Accepted. Amends [decision 4](0004-parsing-markdown-without-a-design-system.md), whose choice of
parser stands and whose choice of renderer this replaces.

## Context

[Decision 4](0004-parsing-markdown-without-a-design-system.md) put `agui-markdown` on
`com.mikepenz:multiplatform-markdown-renderer`: intellij-markdown underneath for the parse, a
Compose layer on top for the drawing, and an incremental parser that made the module's `streaming`
flag mean something. That was the right dependency for a module that had to exist in a day.

It became the wrong one when the Kotlin floor came into question
([#20](https://github.com/uny/agui-compose/issues/20)). Every release of the renderer since
0.42.0 -- the one that added the incremental parser -- is built on Kotlin 2.4, and a klib built
on 2.4 is refused by a 2.3 compiler. The repository's floor was therefore whichever of two
dependencies asked for more, and this was one of the two. The last 2.3-built release, 0.41.0,
compiles against neither `rememberStreamingMarkdownState` nor `MarkdownAlertColors`, so stepping
back to it means losing the streaming parser and the alert palette to keep the floor.

The two layers were not equally at fault. intellij-markdown itself is built on Kotlin 2.0.0 and
readable from any 2.3.x; 0.7.14 was released the day this was spiked. Only the Compose layer
carried the 2.4 build.

### What this module actually draws

An agent's prose: headings, emphasis, lists, code blocks, links, block quotes, alerts, tables.
No images fetched, no custom typography model beyond what decision 4 already takes as constructor
parameters, no plugin surface. That is a bounded element set, and the parser's tree for it is a
stable public API with named element and token types.

### The spike

One day on a branch
([#21](https://github.com/uny/agui-compose/issues/21#issuecomment-5714011494)), before committing
to the rewrite, against a checklist written before the code was. What it measured:

- The existing renderer test suite passes unchanged -- the incremental behaviour those tests pin
  is a property of this module's contract, not of the previous parser's API.
- Size: about a thousand lines of `commonMain`, a quarter of them the style types that used to be
  imported; inside the estimate.
- Streaming cost, on a 33 KB run fed eight characters at a time: 1.16 ms per token re-parsing
  from the top, 13 µs per token with the scheme below. The from-the-top figure grows with the run;
  the other does not.
- With the catalog on Kotlin 2.3.21 and nothing else changed, the module assembles, its tests
  pass on every target, and `checkKotlinAbi` is green against the committed dumps.

## Decision

**`agui-markdown` depends on `org.jetbrains:markdown` directly, as `api`, and draws the tree
itself.** The renderer dependency is gone. `MarkdownFlavourDescriptor` stays on the public surface
because a caller choosing a dialect has to be able to name one; nothing else of the parser's does.

**The style types are this module's own.** `MarkdownColors`, `MarkdownAlertColors` and
`MarkdownTypography` are `@Immutable` classes in this package, with the property names the previous
types had so that a caller's factory lambdas do not change. `MarkdownTypography` has twelve slots
rather than the sixteen the old factory filled: `paragraph`, `ordered`, `bullet` and `list` had no
use distinct from `text`, and a slot nobody reads is a promise nobody can keep.

**Streaming is a settled-prefix scheme, not an incremental parser.** intellij-markdown parses a
string from the top; what bounds the cost is that an agent's prose settles as it goes. Everything
before the last blank line that is followed by a block start is parsed once and kept; only the open
tail is re-parsed per token. Two exceptions are recognised, because a blank line is not always a
boundary: inside an open code fence it is content, and before a list item or an indented line it
continues the block rather than ending it. The settled tree is the same tree a from-the-top parse
gives, and that is asserted rather than assumed.

**Correctness does not depend on the scheme.** A run that is not an extension of what has been
parsed -- a regenerated message, a renderer reused across two runs -- is parsed again from the top,
and a finished run is parsed whole. `AguiTextRenderer`'s contract already says a renderer is free
to re-parse the whole string on every recomposition; this module does something cleverer as a cost
optimisation, and the tests would pass if it did not.

**The parse happens in composition, synchronously.** Both paths, not only the finished one.
Decision 4's reason stands -- the frame a run finishes on must not draw an empty box -- and with no
asynchronous parser to defer to, there is no longer a second way to do it.

**Delimiters are dropped only inside the element that owns them.** The parser keeps every
delimiter as a token: the asterisks of `**bold**` are `EMPH` tokens inside a `STRONG` element, and
so is an asterisk in running prose. What makes one syntax and the other text is the element around
it, so the inline walk drops a token only when it is the delimiter of the element being drawn. An
unresolved `[reference]` stays as its bracketed characters, an unhandled construct degrades to the
literal Markdown, and nothing degrades to nothing.

## Consequences

The public surface changes, and `0.2.0` carries it: a consumer of `0.1.0` who named
`com.mikepenz.markdown.model.MarkdownColors` names `dev.ynagai.agui.markdown.MarkdownColors`
instead. The renderer's constructor keeps its shape, so a consumer who only wrote the two factory
lambdas decision 4 prescribes recompiles without edits.

The module no longer pins the Kotlin floor. What does is `a2ui-core`
([uny/a2ui-compose#64](https://github.com/uny/a2ui-compose/issues/64)), and the plan in #20 is now
one dependency shorter.

The rendering layer is this repository's to maintain. What that costs is a known list rather than
an open one: task-list checkboxes are drawn as their `[ ]` characters, raw HTML is drawn as text,
footnotes and math are not in the element set, and a link reference definition on the other side
of a streaming settle point from its reference does not resolve until the run finishes, because
resolution is per segment.
None of these are things an agent writes often, and each is a bounded change to one file when one
does.

The alert palette is now this module's. The values are the hues GitHub draws its alerts in, close
to its default themes rather than copied from them; a caller who wants an exact palette passes
their own, and the luminance rule decision 4 recorded for choosing between the light and dark sets
is unchanged.

The streaming measurement lives in `jvmTest` behind a system property, so the number decision
records can be re-taken but does not tax every test run.

## What would change the answer

A 2.3-built release of the renderer this module left would not: the reason to own the layer was
never only the Kotlin version, and once the layer is written, a dependency that does the same job
is a dependency for nothing.

intellij-markdown moving to a Kotlin build a 2.3 compiler cannot read would put the module back
where it was, one layer down. That is a parser swap, which decision 4 priced and this decision
makes cheaper: the tree walk is the only thing that names the parser's types.

An incremental parser in intellij-markdown itself would make the settled-prefix scheme
unnecessary, and it would be removed rather than kept alongside.
