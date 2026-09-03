import json
import os
from pydantic import BaseModel
from dotenv import load_dotenv

load_dotenv()

ALLOWED_INTENTS = {
    "open", "open_chat", "call", "search", "type",
    "type_and_send", "send", "tap", "scroll", "back", "home"
}


class PlannedStep(BaseModel):
    intent: str
    target: str


class PlanResponse(BaseModel):
    steps: list[PlannedStep]


def build_planner_prompt(command: str, reply_language: str) -> str:
    return f"""You are planning how to operate an Android phone by hand, one step at a
time, to accomplish what the user asked for. Think like a person holding
the phone: what would you actually need to tap, open, search, or type,
in order, to get this done - even if the user's sentence doesn't
explicitly list steps.

User command: "{command}"
Reply language: {reply_language}

Break it into an ordered list of steps. Each step's "intent" must be
exactly one of:
open, open_chat, call, search, type, type_and_send, send, tap, scroll, back, home

Rules:
1. If the command is already one simple action, return exactly one step.
2. Do NOT split "type X and send it" into separate type + send steps -
   use a single "type_and_send" step.
3. "target" is a short plain string describing what that step is about
   (an app name, a contact, a search query, a scroll direction, or what
   to look for/select on screen).
4. Order steps the way a person would actually have to perform them.
5. Return ONLY valid raw JSON, no markdown, no explanation.

JSON format:
{{"steps": [{{"intent": "...", "target": "..."}}]}}
"""


def _clean_json(text: str) -> dict:
    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end == -1:
        raise ValueError("No JSON object found")
    return json.loads(text[start:end + 1])


# =====================================================================
# OPTION A: Ollama (local) - UNCOMMENT THIS BLOCK TO USE
# =====================================================================
import requests

OLLAMA_BASE_URL = os.getenv("OLLAMA_BASE_URL", "http://127.0.0.1:11434")
OLLAMA_PLANNER_MODEL = os.getenv("OLLAMA_PLANNER_MODEL", "qwen2.5:1.5b")

def _call_model(prompt: str) -> str:
    url = f"{OLLAMA_BASE_URL.strip('/')}/api/chat"
    payload = {
        "model": OLLAMA_PLANNER_MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "stream": False,
        "format": "json",
        "keep_alive": "30m",
        "options": {"temperature": 0, "num_predict": 400, "top_k": 1, "top_p": 0.1}
    }
    response = requests.post(url, json=payload, timeout=90)
    response.raise_for_status()
    return response.json()["message"]["content"]


# =====================================================================
# OPTION B: Hugging Face (langchain_huggingface) - COMMENT OUT OPTION A
# ABOVE AND UNCOMMENT THIS BLOCK TO USE INSTEAD
# =====================================================================
# from langchain_huggingface import HuggingFaceEndpoint, ChatHuggingFace
#
# llm = HuggingFaceEndpoint(
#     repo_id="MiniMaxAI/MiniMax-M2.5",
#     task="text-generation",
#     max_new_tokens=400,
# )
# hf_model = ChatHuggingFace(llm=llm)
#
# def _call_model(prompt: str) -> str:
#     response = hf_model.invoke(prompt)
#     return response.content


def plan_command(command: str, reply_language: str) -> PlanResponse:
    base_prompt = build_planner_prompt(command, reply_language)

    for attempt in range(2):
        try:
            prompt = base_prompt if attempt == 0 else (
                base_prompt + "\n\nOutput ONLY the JSON object. No explanation, no extra text."
            )
            text = _call_model(prompt)
            print(f"RAW PLANNER OUTPUT (attempt {attempt}):", repr(text))

            data = _clean_json(text)
            steps = [
                PlannedStep(intent=s["intent"], target=s["target"])
                for s in data.get("steps", [])
                if s.get("intent") in ALLOWED_INTENTS and s.get("target")
            ]

            if steps:
                return PlanResponse(steps=steps)

        except Exception as e:
            print(f"Planner failed (attempt {attempt}):", e)

    return PlanResponse(steps=[PlannedStep(intent="tap", target=command)])