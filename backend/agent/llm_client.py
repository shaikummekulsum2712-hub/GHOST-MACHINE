import os
import requests
from dotenv import load_dotenv
from agent.action_schema import ActionResponse
from agent.prompt_builder import build_vision_prompt
import base64
from pathlib import Path
import json

load_dotenv()

NVIDIA_API_KEY = os.getenv("NVIDIA_API_KEY")
NVIDIA_MODEL = os.getenv("NVIDIA_MODEL")
NVIDIA_BASE_URL = os.getenv("NVIDIA_BASE_URL")

if not NVIDIA_API_KEY:
    raise RuntimeError("NVIDIA_API_KEY is missing in backend/.env")

if not NVIDIA_MODEL:
    raise RuntimeError("NVIDIA_MODEL is missing in backend/.env")

if not NVIDIA_BASE_URL:
    raise RuntimeError("NVIDIA_BASE_URL is missing in backend/.env")




def encode_image_to_base64(image_path: str)-> str:
    image_file = Path(image_path)

    if not image_file.exists():
        raise FileNotFoundError(f"Screenshot not found: {image_path}")

    image_bytes = image_file.read_bytes()
    encoded_image = base64.b64encode(image_bytes).decode("utf-8")

    return encoded_image





def call_vision_model(command: str,screenshot_path: str) -> ActionResponse:
    # Sends command + screenshot to NVIDIA VLM and returns ActionResponse.

    prompt = build_vision_prompt(command)
    # print("PROMPT VALUE:", prompt)
    image_base64 = encode_image_to_base64(screenshot_path)

    # calling actual vlm
    url = f"{NVIDIA_BASE_URL}/chat/completions"

    headers = {
          "Authorization": f"Bearer {NVIDIA_API_KEY}",
          "Content-Type": "application/json",
    }


    image_data_url = f"data:image/png;base64,{image_base64}"

    payload = {
        "model": NVIDIA_MODEL,
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
                            "url": f"data:image/png;base64,{image_base64}"
                        }
                    }
                ]
            }
        ],
        "temperature": 0,
        "max_tokens": 150
    }

    response = requests.post(
        url,
        headers=headers,
        json=payload,
        timeout=60
    )

    print("NVIDIA status code:", response.status_code)
    print("NVIDIA raw response:", response.text)

    try:
        response.raise_for_status()
    except Exception as e:
        print("NVIDIA API error:", e)
        return ActionResponse(
            action="ask_user",
            x=None,
            y=None,
            text=None,
            direction=None,
            reason="NVIDIA API failed. Please retry.",
            confidence=1.0
        )

    response_json = response.json()

    model_text = response_json["choices"][0]["message"]["content"]

    print("NVIDIA model text:", model_text)
    try:
        action_dict = json.loads(model_text.strip())
        action_resp = ActionResponse(**action_dict)
        return action_resp
    except Exception as e:
        print("Failed to parse NVIDIA model text:", e)
        return ActionResponse(
            action="ask_user",
            x=None,
            y=None,
            text=None,
            direction=None,
            reason="VLM did not return valid action JSON.",
            confidence=1.0
        )
