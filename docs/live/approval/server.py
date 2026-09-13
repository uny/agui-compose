"""An AG-UI server whose one tool waits for the user's approval before it runs.

This is the producer `LiveApprovalTest` measures against: AWS Strands with
``ToolBehavior(interrupt_on_call=True)`` on a server-executed tool, which is the only shipped
adapter that publishes a ``responseSchema`` on its interrupt. None of upstream's own examples turns
that behaviour on, hence this file.

The model is whichever ``MODEL_PROVIDER`` / ``*_API_KEY`` names -- ``openai`` (``OPENAI_API_KEY``),
``anthropic`` (``ANTHROPIC_API_KEY``) or ``gemini`` (``GOOGLE_API_KEY``) -- and it is not what the
test measures; any model that follows the system prompt and calls the tool will do.
"""
import os

os.environ.setdefault("OTEL_SDK_DISABLED", "true")
os.environ.setdefault("OTEL_PYTHON_DISABLED_INSTRUMENTATIONS", "all")

import uvicorn
from strands import Agent, tool
from ag_ui_strands import StrandsAgent, StrandsAgentConfig, ToolBehavior, create_strands_app


def model():
    provider = os.getenv("MODEL_PROVIDER", "openai").lower()
    if provider == "openai":
        from strands.models.openai import OpenAIModel
        return OpenAIModel(client_args={"api_key": os.environ["OPENAI_API_KEY"]}, model_id=os.getenv("MODEL_ID", "gpt-5.4"))
    if provider == "anthropic":
        from strands.models.anthropic import AnthropicModel
        return AnthropicModel(client_args={"api_key": os.environ["ANTHROPIC_API_KEY"]}, model_id=os.getenv("MODEL_ID", "claude-sonnet-4-6"), max_tokens=2048)
    if provider == "gemini":
        from strands.models.gemini import GeminiModel
        return GeminiModel(client_args={"api_key": os.environ["GOOGLE_API_KEY"]}, model_id=os.getenv("MODEL_ID", "gemini-2.5-flash"))
    raise ValueError(f"Unknown MODEL_PROVIDER: {provider}")


@tool
def transfer(amount: int, to: str) -> str:
    """Transfer money to someone.

    Args:
        amount: How much to transfer.
        to: Who receives it.
    """
    return f"Transferred {amount} to {to}."


agent = StrandsAgent(
    agent=Agent(
        model=model(),
        tools=[transfer],
        system_prompt=(
            "You move money. Whenever the user asks to transfer, pay or send money, you MUST call the "
            "`transfer` tool with the amount and recipient. Never ask for confirmation yourself; the "
            "tool is gated. After the tool result, say in one short sentence what happened. If the "
            "transfer was not approved, say so and do not retry."
        ),
    ),
    name="approval",
    description="A transfer tool that waits for the user's approval before it runs",
    config=StrandsAgentConfig(tool_behaviors={"transfer": ToolBehavior(interrupt_on_call=True)}),
)

# No browser talks to this; the sample and the test are not origins.
app = create_strands_app(agent, "/", cors_enabled=False)

if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=int(os.getenv("PORT", "8000")))
