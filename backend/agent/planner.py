import json
from pydantic import BaseModel
from dotenv import load_dotenv
from langchain_huggingface import HuggingFaceEndpoint, ChatHuggingFace

load_dotenv()


ALLOWED_INTENTS = {
    "open", "open_chat", "call", "search", "type",
    "type_and_send", "send", "tap", "scroll", "back", "home"
}


llm = HuggingFaceEndpoint(
    repo_id="MiniMaxAI/MiniMax-M2.5",
    task="text-generation",
)

model = ChatHuggingFace(llm=llm)


class PlannedStep(BaseModel):
    intent: str
    target: str


class PlanResponse(BaseModel):
    steps: list[PlannedStep]


def build_planner_prompt(command: str, reply_language: str) -> str:
    return f"""
You are an Android phone action planner.

User command: "{command}"
Reply language: {reply_language}

Break the command into the minimum number of actions needed.

Each action must have:
- intent: exactly one of:
  open, open_chat, call, search, type,
  type_and_send, send, tap, scroll, back, home
- target: a short description of what to act on.

Rules:
1. If the command needs only one action, return one step.
2. Use "type_and_send" instead of separate "type" and "send".
3. Put steps in the order a person would perform them.
4. Return ONLY valid JSON.
5. Do not add markdown or explanations.

Format:
{{"steps": [{{"intent": "open", "target": "WhatsApp"}}]}}
"""


def _clean_json(text: str) -> dict:
    start = text.find("{")
    end = text.find("}")

    if start == -1 or end == -1:
        raise ValueError("No JSON object found")

    return json.loads(text[start:end + 1])

def plan_command(command: str, reply_language: str) -> PlanResponse:
    prompt = build_planner_prompt(command, reply_language)

    for attempt in range(2):
        try:
            response = model.invoke(prompt)
            text = response.content
            print(f"RAW PLANNER OUTPUT (attempt {attempt}):", repr(text))

            data = _clean_json(text)
            steps = [
                PlannedStep(intent=step["intent"], target=step["target"])
                for step in data.get("steps", [])
                if step.get("intent") in ALLOWED_INTENTS and step.get("target")
            ]

            if steps:
                return PlanResponse(steps=steps)

        except Exception as e:
            print(f"Planner failed (attempt {attempt}):", e)

    return PlanResponse(
          steps=[PlannedStep(intent="tap", target=command)]
          )
