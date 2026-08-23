import json
import os

import requests
from dotenv import load_dotenv
from pydantic import BaseModel

load_dotenv()


HF_TOKEN = os.getenv("HF_TOKEN")

if not HF_TOKEN:
    raise ValueError("HF_TOKEN not found in .env")


MODEL = "Qwen/Qwen2.5-1.5B-Instruct"

HF_URL = "https://router.huggingface.co/featherless-ai/v1/chat/completions"


ALLOWED_INTENTS = {
    "open",
    "open_chat",
    "call",
    "search",
    "type",
    "type_and_send",
    "send",
    "tap",
    "scroll",
    "back",
    "home",
}


class PlannedStep(BaseModel):
    intent: str
    target: str


class PlanResponse(BaseModel):
    steps: list[PlannedStep]


def build_planner_prompt(command: str, reply_language: str) -> str:
    return f"""
You are an Android command planner.

Convert the user's command into ordered actions.

Command: "{command}"
Language: {reply_language}

Allowed intents:
open, open_chat, call, search, type, type_and_send, send, tap, scroll, back, home

Rules:
1. A simple command gets exactly one step.
2. Keep actions in the original order.
3. "type X and send it" must use type_and_send.
4. target must be short and plain.
5. Return ONLY JSON.
6. No explanation.
7. No markdown.

Example:

{{
    "steps": [
        {{
            "intent": "open",
            "target": "whatsapp"
        }}
    ]
}}
"""


def plan_command(
    command: str,
    reply_language: str = "English",
) -> PlanResponse:

    prompt = build_planner_prompt(
        command,
        reply_language,
    )

    headers = {
        "Authorization": f"Bearer {HF_TOKEN}",
        "Content-Type": "application/json",
    }

    payload = {
        "model": MODEL,
        "messages": [
            {
                "role": "user",
                "content": prompt,
            }
        ],
        "temperature": 0,
        "max_tokens": 200,
    }

    try:

        response = requests.post(
            HF_URL,
            headers=headers,
            json=payload,
            timeout=60,
        )

        response.raise_for_status()

        data = response.json()

        model_text = data["choices"][0]["message"]["content"]

        print("Model response:", model_text)

        first_brace = model_text.find("{")
        last_brace = model_text.rfind("}")

        if first_brace == -1 or last_brace == -1:
            raise ValueError("Model did not return JSON")

        clean_json = model_text[
            first_brace:last_brace + 1
        ]

        parsed = json.loads(clean_json)

        valid_steps = []

        for step in parsed.get("steps", []):

            intent = step.get("intent", "")
            target = step.get("target", "")

            if intent in ALLOWED_INTENTS and target:
                valid_steps.append(
                    PlannedStep(
                        intent=intent,
                        target=target,
                    )
                )

        if not valid_steps:
            raise ValueError("No valid steps returned")

        return PlanResponse(
            steps=valid_steps
        )

    except Exception as e:

        print("Planner failed:", e)

        return PlanResponse(
            steps=[
                PlannedStep(
                    intent="tap",
                    target=command,
                )
            ]
        )