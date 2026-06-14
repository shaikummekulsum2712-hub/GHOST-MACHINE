import os
import re
import json
import base64
from pathlib import Path

import requests
from dotenv import load_dotenv

from agent.action_schema import ActionResponse
from agent.prompt_builder import build_vision_prompt


load_dotenv()

OLLAMA_BASE_URL = os.getenv("OLLAMA_BASE_URL", "http://127.0.0.1:11434")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen3-vl:2b")


def encode_image_to_base64(image_path: str) -> str:
    image_file = Path(image_path)

    if not image_file.exists():
        raise FileNotFoundError(f"Screenshot not found: {image_path}")

    return base64.b64encode(image_file.read_bytes()).decode("utf-8")


def clean_model_json(model_text: str) -> str:
    cleaned = model_text.strip()

    if cleaned.startswith("```json"):
        cleaned = cleaned.replace("```json", "", 1).strip()

    if cleaned.startswith("```"):
        cleaned = cleaned.replace("```", "", 1).strip()

    if cleaned.endswith("```"):
        cleaned = cleaned[:-3].strip()

    first_brace = cleaned.find("{")
    last_brace = cleaned.rfind("}")

    if first_brace == -1 or last_brace == -1:
        raise ValueError("No JSON object found in model response")

    cleaned = cleaned[first_brace:last_brace + 1]

    # Fix bad coordinate mistake:
    # "x": 386, 223,
    # -> "x": 386,
    cleaned = re.sub(
        r'("x"\s*:\s*-?\d+(?:\.\d+)?),\s*-?\d+(?:\.\d+)?\s*,',
        r'\1,',
        cleaned
    )

    cleaned = re.sub(
        r'("y"\s*:\s*-?\d+(?:\.\d+)?),\s*-?\d+(?:\.\d+)?\s*,',
        r'\1,',
        cleaned
    )

    return cleaned


def build_action_response_from_text(model_text: str) -> ActionResponse:
    clean_json = clean_model_json(model_text)
    action_dict = json.loads(clean_json)

    action_dict.setdefault("element_id", None)
    action_dict.setdefault("grid_cell", None)
    action_dict.setdefault("x", None)
    action_dict.setdefault("y", None)
    action_dict.setdefault("text", None)
    action_dict.setdefault("direction", None)
    action_dict.setdefault("target_text", None)
    action_dict.setdefault("target_description", None)
    action_dict.setdefault("reason", "No reason provided.")
    action_dict.setdefault("confidence", 0.8)

    return ActionResponse(**action_dict)


def fallback_response(reason: str) -> ActionResponse:
    return ActionResponse(
        action="ask_user",
        element_id=None,
        grid_cell=None,
        x=None,
        y=None,
        text=None,
        direction=None,
        target_text=None,
        target_description=None,
        reason=reason,
        confidence=1.0
    )


def call_vision_model(
    command: str,
    screenshot_path: str,
    screen_elements_json: str | None = None,
    parsed_intent: str | None = None,
    parsed_target: str | None = None,
    android_uncertainty: str | None = None,
    previous_action: str | None = None
) -> ActionResponse:
    prompt = build_vision_prompt(
        command=command,
        screen_elements_json=screen_elements_json,
        parsed_intent=parsed_intent,
        parsed_target=parsed_target,
        android_uncertainty=android_uncertainty,
        previous_action=previous_action
    )

    image_base64 = encode_image_to_base64(screenshot_path)

    url = f"{OLLAMA_BASE_URL.rstrip('/')}/api/chat"

    payload = {
        "model": OLLAMA_MODEL,
        "messages": [
            {
                "role": "user",
                "content": prompt,
                "images": [image_base64]
            }
        ],
        "stream": False,
        "format": "json",
        "keep_alive": "30m",
        "options": {
            "temperature": 0,
            "num_predict": 80,
            "num_ctx": 2048,
            "top_k": 1,
            "top_p": 0.1
        }
    }

    try:
        response = requests.post(url, json=payload, timeout=180)

        print("Ollama status code:", response.status_code)
        print("Ollama raw response:", response.text)

        response.raise_for_status()

        response_json = response.json()
        model_text = response_json["message"]["content"]

        print("Ollama model text:", model_text)

        return build_action_response_from_text(model_text)

    except Exception as e:
        print("Ollama failed:", e)

        return fallback_response(
            reason="Local model failed. Please try again."
        )