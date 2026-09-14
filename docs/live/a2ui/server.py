"""An AG-UI server with nothing A2UI about it, so that whatever draws was asked for by the client.

This is what `A2uiRequest` is measured against: AWS Strands with a plain agent and no ``a2ui``
config. The adapter injects ``generate_a2ui`` only when a run's ``forwardedProps`` carry
``injectA2UITool`` (or the server opts in, which this one does not), and it lifts the ``A2UI
Component Schema`` context entry the same run carries into run state, where the catalog id is read
off it and the sub-agent's prompt lists it as ``## Available Components`` -- exactly the two pieces
``A2uiRequest`` produces. A surface here is therefore the client's doing; a plain text answer is
the client's failure, or the model's. The system prompt is upstream's, verbatim, and names the
tool; a plain prompt has not been measured.

Upstream's own ``a2ui_dynamic_schema`` example also injects only on the client's flag, but stamps
its own catalog id and hands the sub-agent a hand-written composition guide from the server side,
which the sub-agent reads alongside the client's component list. ``A2UI_SERVER_GUIDE=1`` turns
that on, verbatim, so a model that does not draw from the catalog alone can be told apart from one
that does not draw at all.

The model is whichever ``MODEL_PROVIDER`` / ``*_API_KEY`` names -- ``openai`` (``OPENAI_API_KEY``),
``anthropic`` (``ANTHROPIC_API_KEY``) or ``gemini`` (``GOOGLE_API_KEY``). OpenAI is driven through
Chat Completions rather than Responses, as upstream's example insists: the Responses model buffers
tool-call argument deltas, which is what streams a surface progressively.

``AGUI_TRACE=path`` appends every request body and every byte of every response to that file, so
a measurement leaves more than a screenshot behind.
"""
import os

os.environ.setdefault("OTEL_SDK_DISABLED", "true")
os.environ.setdefault("OTEL_PYTHON_DISABLED_INSTRUMENTATIONS", "all")

import uvicorn
from strands import Agent
from ag_ui_strands import StrandsAgent, StrandsAgentConfig, create_strands_app


def model():
    provider = os.getenv("MODEL_PROVIDER", "openai").lower()
    if provider == "openai":
        from strands.models.openai import OpenAIModel
        return OpenAIModel(client_args={"api_key": os.environ["OPENAI_API_KEY"]}, model_id=os.getenv("MODEL_ID", "gpt-5.4"))
    if provider == "anthropic":
        from strands.models.anthropic import AnthropicModel
        # Strands makes the caller choose max_tokens. A surface of three or four cards is a long tool
        # call, and a cut-off call would look like a refusal to draw, so give it room.
        return AnthropicModel(client_args={"api_key": os.environ["ANTHROPIC_API_KEY"]}, model_id=os.getenv("MODEL_ID", "claude-sonnet-4-6"), max_tokens=8192)
    if provider == "gemini":
        from strands.models.gemini import GeminiModel
        return GeminiModel(client_args={"api_key": os.environ["GOOGLE_API_KEY"]}, model_id=os.getenv("MODEL_ID", "gemini-2.5-flash"))
    raise ValueError(f"Unknown MODEL_PROVIDER: {provider}")


# Upstream's `a2ui_dynamic_schema` example, verbatim: the dojo's catalog id and the guide it writes
# for the sub-agent. Off by default, because the point of this server is to send neither.
DOJO_CATALOG_ID = "https://a2ui.org/demos/dojo/dynamic_catalog.json"

COMPOSITION_GUIDE = """
## Available Pre-made Components

You have 3 card components. Use Row as the root with structural children to
repeat a card per item.

### Row
Layout container. Repeat a card template via structural children:
  {"id":"root","component":"Row","children":{"componentId":"card","path":"/items"}}

### HotelCard
Props: name, location, rating (number 0-5), pricePerNight, action

### ProductCard
Props: name, price, rating (number 0-5), description (optional), action

### TeamMemberCard
Props: name, role, department (optional), email (optional), action

## RULES
- Root is ALWAYS a Row with structural children: {"componentId":"<card-id>","path":"/items"}
- ALWAYS include the referenced card component in the components array.
- Inside templates use RELATIVE paths (no leading slash): {"path":"name"}.
- Always provide data in the "data" argument as {"items":[...]}.
- Pick the card type that best matches the request; generate 3-4 realistic items.
"""

SYSTEM_PROMPT = """You are a helpful assistant that creates rich visual UI on the fly.

When the user asks for visual content (product comparisons, dashboards, team
rosters, lists, cards, etc.), use the generate_a2ui tool to create a dynamic
A2UI surface.
IMPORTANT: After calling the tool, do NOT repeat the data in your text response.
The tool renders UI automatically. Just confirm what was rendered."""

server_guide = os.getenv("A2UI_SERVER_GUIDE", "").strip().lower() not in ("", "0", "false", "no", "off")

agent = StrandsAgent(
    agent=Agent(model=model(), system_prompt=SYSTEM_PROMPT),
    name="a2ui",
    description="A plain agent; draws only when the client asks it to",
    config=StrandsAgentConfig(
        a2ui={"default_catalog_id": DOJO_CATALOG_ID, "guidelines": {"composition_guide": COMPOSITION_GUIDE}}
        if server_guide
        else None
    ),
)

# No browser talks to this; the sample is not an origin.
app = create_strands_app(agent, "/", cors_enabled=False)


class Trace:
    """Tees request bodies and response bytes to ``AGUI_TRACE``. ASGI, so the SSE stream is copied
    as it is sent rather than after."""

    def __init__(self, inner, path):
        self.inner = inner
        self.path = path

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.inner(scope, receive, send)
        with open(self.path, "ab") as out:
            out.write(f"\n=== {scope['method']} {scope['path']} ===\n".encode())

            headed = False

            async def receive_traced():
                nonlocal headed
                message = await receive()
                if message["type"] == "http.request" and message.get("body"):
                    if not headed:
                        out.write(b"--- request ---\n")
                        headed = True
                    out.write(message["body"])
                    if not message.get("more_body"):
                        out.write(b"\n")
                return message

            async def send_traced(message):
                if message["type"] == "http.response.start":
                    out.write(f"--- response {message['status']} ---\n".encode())
                elif message["type"] == "http.response.body" and message.get("body"):
                    out.write(message["body"])
                    out.flush()
                await send(message)

            await self.inner(scope, receive_traced, send_traced)


if os.getenv("AGUI_TRACE"):
    app = Trace(app, os.environ["AGUI_TRACE"])

if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=int(os.getenv("PORT", "8000")))
