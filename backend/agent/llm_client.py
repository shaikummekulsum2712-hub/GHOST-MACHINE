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


# 1. NVIDIA / Qwen
NVIDIA_API_KEY = os.getenv("NVIDIA_API_KEY")
NVIDIA_MODEL = os.getenv("NVIDIA_MODEL")
NVIDIA_BASE_URL = os.getenv("NVIDIA_BASE_URL")

# 2. OpenRouter
OPENROUTER_API_KEY = os.getenv("OPENROUTER_API_KEY")
OPENROUTER_MODEL = os.getenv("OPENROUTER_MODEL")
OPENROUTER_BASE_URL = os.getenv("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1")

# 3. Hugging Face
HF_API_KEY = os.getenv("HF_API_KEY")
HF_MODEL = os.getenv("HF_MODEL")
HF_BASE_URL = os.getenv("HF_BASE_URL", "https://router.huggingface.co/v1")

# 4. Gemini
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY")
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-2.5-flash-lite")


def encode_image_to_base64(image_path: str) -> str:
    image_file = Path(image_path)

    if not image_file.exists():
        raise FileNotFoundError(f"Screenshot not found: {image_path}")

    image_bytes = image_file.read_bytes()
    return base64.b64encode(image_bytes).decode("utf-8")


def get_image_mime_type(image_path: str) -> str:
    suffix = Path(image_path).suffix.lower()

    if suffix in [".jpg", ".jpeg"]:
        return "image/jpeg"

    if suffix == ".webp":
        return "image/webp"

    return "image/png"


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

    # Fix common Qwen mistake:
    # "x": 386, 223,
    # becomes:
    # "x": 386,
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

    # Fill missing fields so one bad/missing key does not break the whole app
    action_dict.setdefault("x", None)
    action_dict.setdefault("y", None)
    action_dict.setdefault("text", None)
    action_dict.setdefault("direction", None)
    action_dict.setdefault("element_id", None)
    action_dict.setdefault("target_text", None)
    action_dict.setdefault("target_description", None)
    action_dict.setdefault("reason", "No reason provided.")
    action_dict.setdefault("confidence", 0.8)

    return ActionResponse(**action_dict)


def fallback_response(reason: str) -> ActionResponse:
    return ActionResponse(
        action="ask_user",
        x=None,
        y=None,
        text=None,
        direction=None,
        element_id=None,
        target_text=None,
        target_description=None,
        reason=reason,
        confidence=1.0
    )


def call_openai_style_provider(
    provider_name: str,
    base_url: str,
    api_key: str,
    model: str,
    prompt: str,
    image_base64: str,
    mime_type: str,
    extra_headers: dict | None = None
) -> ActionResponse | None:
    url = f"{base_url.rstrip('/')}/chat/completions"

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    if extra_headers:
        headers.update(extra_headers)

    payload = {
        "model": model,
        "messages": [
            {
                "role": "user",
                "content": [
                    {
                        "type": "text",
                        "text": prompt
                    },
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": f"data:{mime_type};base64,{image_base64}"
                        }
                    }
                ]
            }
        ],
        "temperature": 0,
        "max_tokens": 300
    }

    try:
        response = requests.post(
            url,
            headers=headers,
            json=payload,
            timeout=60
        )

        print(f"{provider_name} status code:", response.status_code)
        print(f"{provider_name} raw response:", response.text)

        response.raise_for_status()

        response_json = response.json()
        model_text = response_json["choices"][0]["message"]["content"]

        print(f"{provider_name} model text:", model_text)

        return build_action_response_from_text(model_text)

    except Exception as e:
        print(f"{provider_name} failed:", e)
        return None


def call_qwen(
    prompt: str,
    image_base64: str,
    mime_type: str
) -> ActionResponse | None:
    if not NVIDIA_API_KEY or not NVIDIA_MODEL or not NVIDIA_BASE_URL:
        print("Qwen skipped: NVIDIA env values missing")
        return None

    return call_openai_style_provider(
        provider_name="NVIDIA/Qwen",
        base_url=NVIDIA_BASE_URL,
        api_key=NVIDIA_API_KEY,
        model=NVIDIA_MODEL,
        prompt=prompt,
        image_base64=image_base64,
        mime_type=mime_type
    )


def call_openrouter(
    prompt: str,
    image_base64: str,
    mime_type: str
) -> ActionResponse | None:
    if not OPENROUTER_API_KEY or not OPENROUTER_MODEL:
        print("OpenRouter skipped: OPENROUTER env values missing")
        return None

    return call_openai_style_provider(
        provider_name="OpenRouter",
        base_url=OPENROUTER_BASE_URL,
        api_key=OPENROUTER_API_KEY,
        model=OPENROUTER_MODEL,
        prompt=prompt,
        image_base64=image_base64,
        mime_type=mime_type,
        extra_headers={
            "HTTP-Referer": "http://localhost",
            "X-Title": "Ghost Machine"
        }
    )


def call_huggingface(
    prompt: str,
    image_base64: str,
    mime_type: str
) -> ActionResponse | None:
    if not HF_API_KEY or not HF_MODEL:
        print("Hugging Face skipped: HF env values missing")
        return None

    return call_openai_style_provider(
        provider_name="HuggingFace",
        base_url=HF_BASE_URL,
        api_key=HF_API_KEY,
        model=HF_MODEL,
        prompt=prompt,
        image_base64=image_base64,
        mime_type=mime_type
    )


def call_gemini(
    prompt: str,
    image_base64: str,
    mime_type: str
) -> ActionResponse | None:
    if not GEMINI_API_KEY:
        print("Gemini skipped: GEMINI_API_KEY missing")
        return None

    url = (
        f"https://generativelanguage.googleapis.com/v1beta/"
        f"models/{GEMINI_MODEL}:generateContent"
    )

    headers = {
        "Content-Type": "application/json",
        "x-goog-api-key": GEMINI_API_KEY,
    }

    payload = {
        "contents": [
            {
                "role": "user",
                "parts": [
                    {
                        "text": prompt
                    },
                    {
                        "inline_data": {
                            "mime_type": mime_type,
                            "data": image_base64
                        }
                    }
                ]
            }
        ],
        "generationConfig": {
            "temperature": 0,
            "maxOutputTokens": 300,
            "responseMimeType": "application/json"
        }
    }

    try:
        response = requests.post(
            url,
            headers=headers,
            json=payload,
            timeout=60
        )

        print("Gemini status code:", response.status_code)
        print("Gemini raw response:", response.text)

        response.raise_for_status()

        response_json = response.json()
        model_text = response_json["candidates"][0]["content"]["parts"][0]["text"]

        print("Gemini model text:", model_text)

        return build_action_response_from_text(model_text)

    except Exception as e:
        print("Gemini failed:", e)
        return None


def call_vision_model(
    command: str,
    screenshot_path: str,
    screen_elements_json: str | None = None
) -> ActionResponse:
    prompt = build_vision_prompt(
        command=command,
        screen_elements_json=screen_elements_json
    )

    image_base64 = encode_image_to_base64(screenshot_path)
    mime_type = get_image_mime_type(screenshot_path)

    providers = [
        ("Qwen", call_qwen),
        ("OpenRouter", call_openrouter),
        ("HuggingFace", call_huggingface),
        ("Gemini", call_gemini),
    ]

    for provider_name, provider_func in providers:
        print(f"Trying provider: {provider_name}")

        result = provider_func(
            prompt=prompt,
            image_base64=image_base64,
            mime_type=mime_type
        )

        if result is not None:
            print(f"Provider succeeded: {provider_name}")
            return result

        print(f"Provider failed, moving to next: {provider_name}")

    return fallback_response(
        reason="I could not process this right now. Please try again in a few seconds."
    )