# 6. Saying something

Date: 2026-09-12

## Status

Accepted. Replaces the deferral in [decision 5](0005-running-the-upstream-agent.md)'s consequences.

## Context

Decision 5 left one hole and named it: a user message typed locally had no way into the transcript,
and the first sample application would force the shape. Probing upstream's published API before
writing that sample found the hole is larger than the record says. It is not only that the local
turn cannot be drawn. It cannot be *sent*.

Measured against `kotlin-client` 0.4.1 (`javap` over the published `kotlin-client-jvm` jar, because
the source is not what ships):

- `RunAgentParameters` holds `runId`, `tools`, `context` and `forwardedProps`. There is no
  `messages`.
- `AbstractAgent.getMessages()` is public; **`setMessages()` is protected.** A caller outside the
  class hierarchy cannot add to the history.
- `AbstractAgent.prepareRunAgentInput(parameters)` builds the run input from the agent's own
  `threadId`, `state` and `messages`, defaulting `runId` to a generated one, `tools` and `context`
  to empty lists, and `forwardedProps` to an empty object.

So `AgentSession.run(parameters)` -- everything decision 5 shipped -- can only ever send the messages
the agent was constructed with, plus the ones its own runs produced. A conversation started that way
can never receive a second question.

There is exactly one public way in: the `runAgentObservable(RunAgentInput, AgentSubscriber?)`
overload. Read off the bytecode, it assigns `this.messages = input.messages` and
`this.state = input.state` before it does anything else, then goes through the same `apply` and
`processApplyEvents` as the parameters overload. So an input built by hand is not a bypass of the
agent's state -- it *becomes* the agent's state, and the turn sent through it is history for every
run after it.

The drawing question, then, is downstream of the sending question, and the two alternatives decision
5 named can now be judged:

- **Synthesise a `MESSAGES_SNAPSHOT`.** No ABI change, and the reducer's existing path handles it.
  But a snapshot *replaces* the transcript, and rebuilds it in the shape whose losses decision 1 is
  about: within a restored assistant message the order of a sentence and the tool call that followed
  it is gone. Showing the sender their own line would degrade every turn already on screen, every
  time they sent one. The cost grows with the length of the conversation, which is backwards.
- **Prepend in the UI.** The transcript never contains the user's turn, so the UI has no anchor to
  order against -- not for a turn in the middle of a thread, and not at all once a snapshot arrives
  and renumbers everything.

## Decision

Two additions, one per layer.

`agui-core` gains `UiTranscriptReducer.appendUserMessage(UserMessage): UiTranscript` -- the only
entry point that is not an event. It mints the message through the same id de-duplication as one off
the wire, builds it with the same code the snapshot path uses (one function, two callers), and
**detaches** rather than finalises the open assistant turn: a line sent mid-answer means the next
assistant part belongs in a new bubble, not that the words arriving now stop arriving. What was
streaming is settled when the run ends, exactly as it is for an `ACTIVITY_SNAPSHOT` mid-stream.

`agui-agent` gains `AgentSession.send(UserMessage, RunAgentParameters?)` and a `send(String, …)`
that mints the id. It appends to the transcript and runs the agent, both under the lock a run
already holds, and it builds the `RunAgentInput` with the defaults `prepareRunAgentInput` would have
applied -- measured above, and asserted in a test, because a turn sent through `send` and one sent
through `run` must not reach the server in different shapes. The history it sends is the agent's
`messages`, not this transcript: upstream keeps the thread in the shape the protocol asks a client
to send back, and the transcript keeps the ordering that shape loses. The two are not the same
object and are not meant to be.

`run` stays. A run with no new turn is a real thing -- a retry, a resumed interrupt, a tool result
answered by the next run.

## Consequences

- The append entry breaks `agui-core`'s events-only posture, and that is the price. The reducer is
  no longer a pure function of an event stream; it is a render model with one local mutation. The
  KDoc says so at the entry point, so a reader who expected the stronger property finds out where
  it stops.
- A server that answers with a `MESSAGES_SNAPSHOT` replaces the appended message with its own copy.
  That is the snapshot's job and is tested; the local append is not a claim about what the server
  believes.
- `send` mints message and run ids with `kotlin.uuid.Uuid.random()`, which is stable in Kotlin
  2.4.10 (`@WasExperimental`, no opt-in). Upstream's own generator is private, so the ids are not
  the same shape as the ones the agent would have generated. Nothing on the wire cares, and a client
  that does passes its own.
- A failed run leaves the message on screen. A UI offering a retry needs the line it would retry
  from, and the reducer has no way to take a message back.
- Still not addressed: a local **system** or **developer** message, and editing or deleting a turn
  already sent. All three are additive when something needs them.

## What would change the answer

- Upstream adding `messages` to `RunAgentParameters`, or making `setMessages` public: `send` would
  stop building the input by hand and the defaults test would become redundant rather than load-
  bearing.
- A protocol event for a client-originated message: the append entry would become a fold like every
  other, and `agui-core` would get its events-only posture back.
- A second local mutation -- editing a sent turn, say -- would be the signal that the render model
  wants a small mutation API rather than one more method, and that it belongs beside the reducer
  rather than on it.
