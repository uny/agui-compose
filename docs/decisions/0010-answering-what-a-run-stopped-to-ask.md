# 10. Answering what a run stopped to ask

Date: 2026-09-13

## Status

Accepted. Supersedes the last paragraph of decision 8's consequences -- "human-in-the-loop is
reachable today: the run stops interrupted, the client `send`s or `run`s when it is ready" -- and
the behaviour `AgentSession` had on its strength.

## Context

Decision 8 left one thing open: what a *pending approval* looks like in the model. `ToolCallStatus`
had no "awaiting a human" state, and adding one is an `agui-model` ABI change the record said should
follow a measurement of what upstream's interrupt outcome actually carries. This is that
measurement, taken against `ag-ui-protocol/ag-ui` at `7479336` and the published `kotlin-core`
0.4.1, and what it decided.

**What an interrupt carries.** `RUN_FINISHED` with `outcome: interrupt` carries a list of
`Interrupt`, each with `id`, `reason`, and optionally `message`, `toolCallId`, `responseSchema`,
`expiresAt` and `metadata`. The Kotlin SDK has the type, field for field. `toolCallId` is
*optional*, and the producers disagree about it: AWS Strands' tool approval names the call, with
`reason: "tool_call"`, `message: "Approve call to <tool>?"`, a `responseSchema` of
`{ approved: boolean }` and the tool's name and input in `metadata`; LangGraph's bridge maps a
graph's raw `interrupt(value)` and names no call. So an approval is not a property of a tool call.
It is a property of the run, which sometimes names a call.

**How a client answers.** The spec draft (`docs/spec/draft/basic/patterns/interrupt-resume.mdx`) is
unambiguous, and it is not what decision 8 assumed. A run that stopped for a *frontend tool*
finishes as success; the result rides the next input's messages, which is what decision 8 built.
A run that stopped to *ask* finishes with the interrupt outcome, and the run that continues it
carries the answers in the input's `resume` list -- one `ResumeEntry` per interrupt, `resolved`
with a payload or `cancelled`. Two rules attach: the list MUST cover every interrupt of the run
being continued ("omission is not abandonment"), and a resuming input that leaves one uncovered is
rejected before the run starts. The reference client (`sdks/typescript/packages/client`,
`agent.ts`) keeps a `pendingInterrupts` list, set on every `RUN_FINISHED` and left alone by
`RUN_ERROR`, and throws from any run that does not cover it -- `send` and `run` alike.

Two things follow for this library. A tool result is not an answer to an interrupt, so
`AgentSession.answerTools` continuing an interrupted run with the tool's result -- which decision 8
described and a test asserted -- was sending the wrong thing: a run the server would refuse, or
worse, accept and proceed past the question with. And decision 8's "the client `send`s or `run`s
when it is ready" was describing a run the protocol now says may not start.

**What the Kotlin client can carry.** `kotlin-core` 0.4.1's `RunAgentInput` has `resume`;
`kotlin-client` 0.4.1's `RunAgentParameters` does not, and `prepareRunAgentInput` leaves the field
null. Decision 6 already built the input in `AgentSession` for the same reason (`RunAgentParameters`
carries no messages), so `resume` goes in through the door `send` opened.

## Decision

**An interrupt belongs to the run.** `RunState.Finished.interrupted: Boolean` becomes
`interrupts: List<UiInterrupt>`, the protocol's `Interrupt` mirrored whole -- every field is
something a renderer may need -- with `interrupted` kept as a derived property. `UiResumeEntry`
mirrors `ResumeEntry`. Both live in `agui-model` because `agui-compose` has to draw the question and
express the answer, and neither may name a protocol type (decision 2).

**A call an interrupt names is told it is waiting.** `ToolCallStatus.AWAITING_APPROVAL`, set by the
reducer on the call an interrupt's `toolCallId` names, when that call is still awaiting a result;
a call that already has one is not waiting on anyone, whatever the producer says. The status is
released to `AWAITING_RESULT` when the next run starts, because the protocol guarantees that every
interrupt was answered or abandoned by then, and a declined call may never hear anything again -- a
status that still said "waiting on the client" would be a lie for ever. The status carries no data:
the interrupt's prompt and schema are on the run, and a renderer that draws the approval inside the
tool-call slot looks them up by id.

**`AgentSession.resume(entries)` is the one call that continues an interrupted thread.** It checks
coverage the way the reference client does -- every pending interrupt named, nothing else named,
nothing named twice -- and throws before the run starts otherwise. `run` and `send` throw while
the thread is interrupted, for the same reason the reference client's do: the alternative is a run
the server refuses, or one that proceeds past a question nobody answered. `send` checks before the
message reaches the screen, so a refused line is not drawn and then failed. `answerTools` stops at
an interrupted run; a tool result folded while the thread is interrupted is kept the way a failed
run's is, and goes out with the resume, placed after its call.

**A failed resume is still owed.** The session keeps the pending list across a `RUN_ERROR`, as the
reference client does, and keeps the entries the failed run carried: `run` -- the retry for any
failed run -- sends them again, and a second `resume` is a retry too, since the questions are
still open. `pendingInterrupts` on the session, a `StateFlow`, is where a UI observes them once
the transcript has stopped naming them, since `RunState.Failed` names none.

**`expiresAt` is carried, not judged.** The protocol leaves its format to the producer and the
judgment to the consumer; the reference client parses it as a date and throws on a late answer. This
library does not: `agui-model` takes no datetime dependency (decision 7 is about the one that
arrives anyway), and a producer that will not take a late answer says so by failing the run, which
`resume` returns as `RunState.Failed`. A client that wants to stop offering an expired question
reads the field itself.

**Drawing it.** `AguiComponents.interrupt` is the slot, `AguiInterrupts(interrupts, onResume)` is
the composable, and it is *not* drawn by `AguiTranscript`: an interrupt is the thread's status, not
a message, and where a question waiting on the reader belongs is the application's layout decision
-- the same line `AguiTranscript` already draws for `run` and `steps`. The default slot draws the
prompt and no way to answer it, which is the same honesty as the other defaults and costs more
here; `agui-material3` draws a card with two buttons. The affirmative resolves with
`{"approved": true}` when `responseSchema` names an `approved` property and with no payload
otherwise; the negative *abandons* the interrupt rather than resolving it with `false`, because
abandoning is the answer every producer must accept, and Strands -- the one producer with a schema
on the wire -- distinguishes the two and reaches the tool with an error for a cancellation, which
is the honest report of a reader who declined. A form built from an arbitrary `responseSchema` is a
library of its own, and an application with a producer that wants one replaces the slot.

**The sample closes the composer** while the thread waits, collects one answer per card, and
resumes when the last one lands.

## Consequences

- `RunState.Finished(interrupted = …)` no longer compiles; `interrupts = listOf(…)` does, and
  `interrupted` still reads. `ToolCallStatus` has a fifth value, so an exhaustive `when` over it
  breaks at compile time (one did, in `agui-a2ui`, and now draws an approval-held render call like
  one awaiting its result). Nothing is published; the ABI dumps are updated.
- `AgentSession.run` and `send` can now throw `IllegalStateException` -- on a thread with a
  pending interrupt -- where before they promised not to throw for a run that failed in an ordinary
  way. That promise stands: a thread that may not run is not a run that failed, it is a
  programming error, and it is reported the way one is. A UI closes its composer on
  `RunState.pendingInterrupts` being non-empty and never sees it.
- `an_interrupted_run_whose_tool_ran_here_is_answered` is inverted:
  `an_interrupted_run_is_not_answered_by_the_tool_that_ran_here`, and the result goes out with
  the resume. The old behaviour was wrong under the spec draft and would have been wrong under any
  producer that emits the interrupt outcome, because none of them reads a tool message as a
  resume entry.
- No recorded traffic exercises this. Upstream's recordings (decision 9) carry no interrupt
  outcome, and the replay fixtures are upstream's verbatim -- fabricating one would put words in a
  producer's mouth under upstream's provenance. `ScriptedAgent` emits the outcome and the session
  and reducer are tested against it; a recording is the tripwire to add when upstream records one.
- Not decided here: how an interrupt raised inside a *subagent* is attributed. The spec draft's
  `subagentRunId` on `Interrupt` is not in `kotlin-core` 0.4.1's type, so there is nothing to
  carry yet. Nor a form for an arbitrary `responseSchema`, nor expiry -- each is the slot's or the
  application's, as above.
