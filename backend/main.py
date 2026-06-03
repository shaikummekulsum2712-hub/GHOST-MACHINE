import sys
import io

# Force UTF-8 encoding on Windows consoles to prevent UnicodeEncodeError crashes
if sys.platform == "win32":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

from fastapi import FastAPI
from fastapi.responses import HTMLResponse
from pydantic import BaseModel
import google.generativeai as genai
import json
import os
import uuid
import time
import base64
from typing import Optional, List
from dotenv import load_dotenv
from device_profile import get_device_context
from frontend import HTML_CONTENT
from PIL import Image

# Load environment variables
load_dotenv()

# Configure Gemini
genai.configure(api_key=os.getenv("GEMINI_API_KEY"))

app = FastAPI(title="Ghost Machine AI Backend")

# ── System prompt that teaches Gemini how to be a phone controller ──
SYSTEM_PROMPT_VISION = f"""You are Ghost Machine, an AI agent that controls an Android phone through accessibility services.
You receive a natural language command from the user along with a SCREENSHOT of the phone's current screen.
You must analyze the screenshot to understand the current UI state and return a JSON array of step-by-step actions to accomplish the command.

## Device Info
{get_device_context()}

## Available Actions

1. **tap** — Tap a point on screen
   {{"action": "tap", "x": <int>, "y": <int>, "reason": "<why>"}}

2. **swipe** — Swipe from one point to another
   {{"action": "swipe", "startX": <int>, "startY": <int>, "endX": <int>, "endY": <int>, "duration": <ms>, "reason": "<why>"}}

3. **type** — Type text into the currently focused input field
   {{"action": "type", "text": "<string>", "reason": "<why>"}}

4. **wait** — Wait before the next action (for app loading, animations, etc.)
   {{"action": "wait", "duration": <ms>, "reason": "<why>"}}

## How to Use the Screenshot

You will receive a screenshot of the phone's current screen. Use it to:
1. **Identify the current app/screen** — What app is open? What screen is shown?
2. **Locate UI elements precisely** — Find exact positions of buttons, text fields, icons, labels by analyzing where they appear in the screenshot
3. **Read text on screen** — Read any visible text, labels, or notifications
4. **Plan navigation** — Determine what steps are needed from the current state to accomplish the goal

When estimating coordinates from the screenshot:
- The screenshot maps to the full screen resolution ({get_device_context().split("Screen resolution: ")[1].split(" pixels")[0]} pixels)
- Tap the CENTER of the target element, not the edge
- Account for the status bar at the top (~100px) and navigation bar at the bottom (~150px)

## Navigation Gestures
- Back: swipe from left edge (startX=0, startY=1400, endX=400, endY=1400)
- Home: swipe up from very bottom (startX=630, startY=2780, endX=630, endY=2400)
- Recent apps: swipe up from bottom and hold (use longer duration ~500ms)
- Open app drawer: swipe up from bottom center (startX=630, startY=2600, endX=630, endY=800)
- Pull down notifications: swipe from top (startX=630, startY=0, endX=630, endY=1400)

## Rules
1. Return ONLY a valid JSON object with two keys: "summary" and "steps"
2. "summary" is a short human-readable explanation of what you'll do (1-2 sentences)
3. "steps" is a JSON array of action objects
4. **USE THE SCREENSHOT** to determine exact coordinates — do NOT guess blindly
5. Add "wait" actions after opening apps (2000-3000ms) to let them fully load
6. Add "wait" actions after taps that trigger navigation or open new screens (1000-1500ms)
7. IMPORTANT: Always add a "wait" action of at least 1500ms BEFORE any "type" action
8. Break complex tasks into small, individual steps
9. Each step must have a "reason" field explaining what it does
10. If you need to open an app, first go to home screen, then open app drawer, then tap the app
11. For typing, first tap the input field, wait 1500ms for keyboard to appear, then use the "type" action
12. Do NOT include any markdown, explanation, or text outside the JSON object
13. If the user asks something that cannot be done via phone actions, return a summary explaining why and an empty steps array
14. LOOK AT THE SCREENSHOT CAREFULLY before deciding coordinates — the visual information is your primary source of truth"""

# Fallback prompt when no screenshot is available (legacy blind mode)
SYSTEM_PROMPT_BLIND = f"""You are Ghost Machine, an AI agent that controls an Android phone through accessibility services.
You receive a natural language command from the user and must return a JSON array of step-by-step actions to accomplish it.

## Device Info
{get_device_context()}

## Available Actions

1. **tap** — Tap a point on screen
   {{"action": "tap", "x": <int>, "y": <int>, "reason": "<why>"}}

2. **swipe** — Swipe from one point to another
   {{"action": "swipe", "startX": <int>, "startY": <int>, "endX": <int>, "endY": <int>, "duration": <ms>, "reason": "<why>"}}

3. **type** — Type text into the currently focused input field
   {{"action": "type", "text": "<string>", "reason": "<why>"}}

4. **wait** — Wait before the next action (for app loading, animations, etc.)
   {{"action": "wait", "duration": <ms>, "reason": "<why>"}}

## Navigation Gestures
- Back: swipe from left edge (startX=0, startY=1400, endX=400, endY=1400)
- Home: swipe up from very bottom (startX=630, startY=2780, endX=630, endY=2400)
- Recent apps: swipe up from bottom and hold (use longer duration ~500ms)
- Open app drawer: swipe up from bottom center (startX=630, startY=2600, endX=630, endY=800)
- Pull down notifications: swipe from top (startX=630, startY=0, endX=630, endY=1400)

## Rules
1. Return ONLY a valid JSON object with two keys: "summary" and "steps"
2. "summary" is a short human-readable explanation of what you'll do (1-2 sentences)
3. "steps" is a JSON array of action objects
4. Use reasonable coordinates based on the device screen size and common Android layouts
5. Add "wait" actions after opening apps (2000-3000ms) to let them fully load
6. Add "wait" actions after taps that trigger navigation (1000-1500ms)
7. IMPORTANT: Always add a "wait" action of at least 1500ms BEFORE any "type" action
8. Break complex tasks into small, individual steps
9. Each step must have a "reason" field explaining what it does
10. For typing, first tap the input field, wait 1500ms for keyboard to appear, then use the "type" action
11. Do NOT include any markdown, explanation, or text outside the JSON object
12. If the user asks something that cannot be done via phone actions, return a summary explaining why and an empty steps array

NOTE: No screenshot is available. You are operating in blind mode — estimate coordinates based on common Android layout conventions."""

# ── VISION LOOP PROMPT: Single-step reactive agent ──
SYSTEM_PROMPT_VISION_LOOP = f"""You are Ghost Machine, an AI agent that controls an Android phone through accessibility services.
You are operating in a VISION LOOP — you receive ONE screenshot at a time and must return exactly ONE action.

## Device Info
{get_device_context()}

## How the Vision Loop Works
1. You receive the user's GOAL (what they want to accomplish)
2. You receive a SCREENSHOT of the phone's current screen
3. You receive a HISTORY of actions you already performed (if any)
4. You must analyze the screenshot and decide the SINGLE NEXT ACTION to take
5. After your action is executed, you'll receive a NEW screenshot showing the result
6. This repeats until the goal is achieved

## Available Actions

1. **tap** — Tap a point on screen
   {{"action": "tap", "x": <int>, "y": <int>, "reason": "<why>"}}

2. **swipe** — Swipe from one point to another
   {{"action": "swipe", "startX": <int>, "startY": <int>, "endX": <int>, "endY": <int>, "duration": <ms>, "reason": "<why>"}}

3. **type** — Type text into the currently focused input field
   {{"action": "type", "text": "<string>", "reason": "<why>"}}

4. **wait** — Wait before the next action (for app loading, animations, etc.)
   {{"action": "wait", "duration": <ms>, "reason": "<why>"}}

5. **done** — The goal has been achieved, stop the loop
   {{"action": "done", "reason": "<explain what was accomplished>"}}

## Navigation Gestures
- Back: swipe from left edge (startX=0, startY=1400, endX=400, endY=1400)
- Home: swipe up from very bottom (startX=630, startY=2780, endX=630, endY=2400)
- Recent apps: swipe up from bottom and hold (use longer duration ~500ms)
- Open app drawer: swipe up from bottom center (startX=630, startY=2600, endX=630, endY=800)
- Pull down notifications: swipe from top (startX=630, startY=0, endX=630, endY=1400)

## Critical Rules
1. Return ONLY a single JSON object — exactly ONE action, NOT an array
2. LOOK AT THE SCREENSHOT to determine exact coordinates — the visual is your primary source of truth
3. Tap the CENTER of the target element, not the edge
4. Account for the status bar (~100px top) and nav bar (~150px bottom)
5. If the previous action didn't have the expected effect (you can see in the screenshot), try a different approach
6. Include a clear "reason" explaining what you see on screen and why you chose this action
7. When the goal is fully achieved, return action "done"
8. If the goal cannot be achieved, return action "done" with reason explaining why
9. For typing: first tap the input field in one step, then type text in the next step
10. Do NOT include markdown, explanation, or text outside the JSON object
11. Think about what you SEE on the screen, not what you assume is there"""

# Initialize the Gemini models — try multiple models for free tier availability
MODEL_NAMES = ["gemini-2.5-flash", "gemini-2.0-flash-lite", "gemini-2.0-flash", "gemini-1.5-flash"]

model = None
selected_model_name = None
for model_name in MODEL_NAMES:
    try:
        model = genai.GenerativeModel(
            model_name=model_name,
            system_instruction=SYSTEM_PROMPT_VISION
        )
        selected_model_name = model_name
        print(f"✅ Using batch model: {model_name}")
        break
    except Exception as e:
        print(f"Model {model_name} unavailable: {e}")

if model is None:
    raise RuntimeError("No Gemini model available. Check your API key.")

# Vision Loop model — same model, different system prompt
vision_loop_model = genai.GenerativeModel(
    model_name=selected_model_name,
    system_instruction=SYSTEM_PROMPT_VISION_LOOP
)
print(f"✅ Using vision loop model: {selected_model_name}")

# Session memory for follow-up commands
chat_session = model.start_chat(history=[])


# Synchronized state between Web Companion UI and Android App
device_state = {
    "connected": False,
    "last_seen": 0.0,
}

pending_command = None

# Latest screenshot from the device (base64 JPEG)
latest_screenshot = {
    "data": None,
    "timestamp": 0.0,
}

execution_status = {
    "id": "",
    "status": "IDLE",
    "current_step": 0,
    "total_steps": 0,
    "current_action": "",
    "current_reason": "",
    "error": None
}

message_history = []

# ── Vision Loop State Machine ──
vision_loop_state = {
    "active": False,
    "goal": "",
    "loop_id": "",
    "step_history": [],     # list of {"step": int, "action": dict, "screenshot_timestamp": float}
    "current_step": 0,
    "max_steps": 25,
    "status": "IDLE",       # IDLE | WAITING_SCREENSHOT | THINKING | WAITING_EXECUTION | DONE | FAILED
    "next_action": None,   # The single action for the phone to execute
    "screenshot_data": None,  # Latest base64 screenshot for web UI display
    "error": None,
}


def reset_vision_loop():
    """Reset vision loop to idle state."""
    global vision_loop_state
    vision_loop_state = {
        "active": False,
        "goal": "",
        "loop_id": "",
        "step_history": [],
        "current_step": 0,
        "max_steps": 25,
        "status": "IDLE",
        "next_action": None,
        "screenshot_data": None,
        "error": None,
    }


def build_vision_loop_prompt(goal: str, step_history: list) -> str:
    """Build the text prompt that includes goal + history of prior actions."""
    prompt = f"""## Goal
{goal}
"""
    if step_history:
        prompt += "\n## Actions Already Taken\n"
        for entry in step_history:
            step_num = entry["step"]
            action = entry["action"]
            act_type = action.get("action", "unknown")
            reason = action.get("reason", "")
            prompt += f"Step {step_num}: [{act_type}] {reason}\n"
        prompt += "\n"
    prompt += """## Current Screenshot
Here is a screenshot of the phone's screen RIGHT NOW, AFTER all the actions above were executed.
Analyze it carefully and decide the SINGLE NEXT ACTION to take toward the goal.
If the goal is already achieved (you can see the expected result on screen), return {"action": "done", "reason": "..."}.
Return ONLY a single JSON object — one action."""
    return prompt
=======
from fastapi import FastAPI, UploadFile, File, Form
from datetime import datetime
from pathlib import Path

from agent.agent_loop import get_next_action as agent_get_next_action
from agent.action_schema import CommandRequest, ActionResponse

>>>>>>> origin/main


<<<<<<< HEAD
class CommandRequest(BaseModel):
    command: str
    sender: str = "web"  # "web" or "android"
    screenshot: Optional[str] = None  # base64-encoded JPEG screenshot


class ScreenshotUpload(BaseModel):
    screenshot: str  # base64-encoded JPEG
    command_id: Optional[str] = None  # ID of the pending command this screenshot is for


class StatusReport(BaseModel):
    id: str
    status: str          # "IDLE", "QUEUED", "DISPATCHED", "EXECUTING", "SUCCESS", "FAILED"
    current_step: int
    total_steps: int
    current_action: str = ""
    current_reason: str = ""
    error: Optional[str] = None


class VisionLoopStart(BaseModel):
    goal: str
    max_steps: int = 25


class VisionLoopScreenshot(BaseModel):
    loop_id: str
    screenshot: str  # base64-encoded JPEG


class VisionLoopActionComplete(BaseModel):
    loop_id: str
    step_index: int
    success: bool
    error: Optional[str] = None


def process_screenshot(base64_data: str) -> Optional[bytes]:
    """Decode, validate, and optionally compress a base64 screenshot.
    Returns the processed image as JPEG bytes, or None on failure."""
    try:
        img_bytes = base64.b64decode(base64_data)
        img = Image.open(io.BytesIO(img_bytes))

        # Resize to 50% to reduce payload size for Gemini while keeping detail
        new_width = img.width // 2
        new_height = img.height // 2
        img = img.resize((new_width, new_height), Image.LANCZOS)

        # Convert to JPEG with moderate compression
        output = io.BytesIO()
        img.save(output, format="JPEG", quality=60)
        processed_bytes = output.getvalue()

        print(f"📸 Screenshot processed: {img.width}x{img.height}, {len(processed_bytes) // 1024}KB")
        return processed_bytes
    except Exception as e:
        print(f"❌ Screenshot processing failed: {e}")
        return None


def generate_with_vision(command: str, screenshot_base64: str) -> str:
    """Send command + screenshot to Gemini using multimodal (vision) API.
    Returns the raw text response from Gemini."""
    img_bytes = process_screenshot(screenshot_base64)
    if img_bytes is None:
        print("⚠️ Screenshot processing failed, falling back to text-only")
        return generate_text_only(command)

    # Create the image part for Gemini
    image_part = {
        "mime_type": "image/jpeg",
        "data": img_bytes
    }

    # Use generate_content with multimodal input (image + text)
    # We use the model directly instead of chat_session because
    # chat sessions don't support inline image parts in all SDK versions
    prompt_text = f"""Here is a screenshot of the phone's current screen. Analyze it carefully.

User command: {command}

Look at the screenshot and generate the precise action steps. Use the visual positions of UI elements you can see to determine exact tap coordinates."""

    response = model.generate_content([prompt_text, image_part])
    return response.text.strip()


def generate_text_only(command: str) -> str:
    """Send command to Gemini as text only (blind mode fallback)."""
    response = chat_session.send_message(command)
    return response.text.strip()


def parse_gemini_response(raw_text: str) -> dict:
    """Parse Gemini's JSON response, handling markdown code fences."""
    # Clean up markdown code fences if Gemini wraps the response
    if raw_text.startswith("```"):
        lines = raw_text.split("\n")
        lines = [l for l in lines if not l.strip().startswith("```")]
        raw_text = "\n".join(lines)

    return json.loads(raw_text)


@app.get("/", response_class=HTMLResponse)
def home():
    return HTML_CONTENT


@app.post("/next-action")
async def get_next_action(request: CommandRequest):
    print(f"\n{'='*50}")
    print(f"User command from {request.sender}: {request.command}")
    print(f"Screenshot included: {'YES ✅' if request.screenshot else 'NO ❌ (blind mode)'}")
    print(f"{'='*50}")

    try:
        # Choose vision or blind mode based on screenshot availability
        if request.screenshot:
            print("🔮 Using VISION mode — AI can see the screen!")
            raw_text = generate_with_vision(request.command, request.screenshot)
        else:
            print("🙈 Using BLIND mode — AI is guessing coordinates")
            raw_text = generate_text_only(request.command)

        # Parse the JSON response
        result = parse_gemini_response(raw_text)
        summary = result.get("summary", "Executing actions...")
        steps = result.get("steps", [])

        print(f"AI Summary: {summary}")
        print(f"Steps: {len(steps)}")
        for i, step in enumerate(steps):
            print(f"  Step {i+1}: {step.get('action')} — {step.get('reason', '')}")

        # Generate unique ID for this execution sequence
        msg_id = str(uuid.uuid4())

        # Save to messages list for browser rendering history
        if request.sender == "web":
            # Add user message
            user_msg = {
                "id": msg_id + "-user",
                "command": request.command,
                "sender": "web",
                "timestamp": time.time()
            }
            message_history.append(user_msg)

            # Add AI message
            ai_msg = {
                "id": msg_id,
                "command": request.command,
                "summary": summary,
                "steps": steps,
                "sender": "gemini",
                "timestamp": time.time() + 0.1,
                "had_screenshot": request.screenshot is not None
            }
            message_history.append(ai_msg)
        else:
            msg_entry = {
                "id": msg_id,
                "command": request.command,
                "summary": summary,
                "steps": steps,
                "sender": request.sender,
                "timestamp": time.time(),
                "had_screenshot": request.screenshot is not None
            }
            message_history.append(msg_entry)

        # If submitted from browser, enqueue it for phone execution
        if request.sender == "web":
            global pending_command, execution_status
            pending_command = {
                "id": msg_id,
                "command": request.command,
                "summary": summary,
                "steps": steps,
                "needs_screenshot": True  # Flag: phone should capture & re-plan
            }
            execution_status = {
                "id": msg_id,
                "status": "QUEUED",
                "current_step": 0,
                "total_steps": len(steps),
                "current_action": "",
                "current_reason": "",
                "error": None
            }
            print(f"📱 Queued command for Android device (ID: {msg_id})")
            print(f"   Phone will capture screenshot and re-plan with vision before executing")

        # Save to action.json for debugging
        file_path = os.path.join(os.path.dirname(__file__), "action.json")
        with open(file_path, "w") as f:
            json.dump(result, f, indent=4)
            print(f"\nSaved action plan to: {file_path}")

        # Return full payload including ID
        return {
            "id": msg_id,
            "summary": summary,
            "steps": steps
        }

    except json.JSONDecodeError as e:
        print(f"JSON parse error: {e}")
        print(f"Raw response: {raw_text}")
        return {
            "id": str(uuid.uuid4()),
            "summary": "I understood your request but had trouble formatting the response. Please try again.",
            "steps": []
        }
    except Exception as e:
        print(f"Gemini API error: {e}")
        return {
            "id": str(uuid.uuid4()),
            "summary": f"Error communicating with AI: {str(e)}",
            "steps": []
        }


@app.post("/upload-screenshot")
async def upload_screenshot(data: ScreenshotUpload):
    """Android uploads a screenshot. If a command_id is provided, re-generate
    the action plan with vision and return the updated steps."""
    global latest_screenshot

    latest_screenshot["data"] = data.screenshot
    latest_screenshot["timestamp"] = time.time()

    print(f"📸 Screenshot received from device ({len(data.screenshot) // 1024}KB base64)")

    if data.command_id:
        # Find the pending command and re-plan with vision
        # Look it up in message_history
        matching_msg = None
        for msg in message_history:
            if msg["id"] == data.command_id:
                matching_msg = msg
                break

        if matching_msg:
            command_text = matching_msg["command"]
            print(f"🔄 Re-planning command '{command_text}' with screenshot vision...")

            try:
                raw_text = generate_with_vision(command_text, data.screenshot)
                result = parse_gemini_response(raw_text)
                summary = result.get("summary", "Executing actions...")
                steps = result.get("steps", [])

                print(f"🔮 Vision re-plan: {len(steps)} steps")

                # Update the message history entry
                matching_msg["summary"] = summary
                matching_msg["steps"] = steps
                matching_msg["had_screenshot"] = True

                return {
                    "status": "replanned",
                    "id": data.command_id,
                    "summary": summary,
                    "steps": steps
                }
            except Exception as e:
                print(f"❌ Vision re-plan failed: {e}")
                return {
                    "status": "replan_failed",
                    "error": str(e)
                }

    return {"status": "ok", "message": "Screenshot stored"}


@app.get("/poll-command")
def poll_command():
    global pending_command, execution_status, device_state
    device_state["connected"] = True
    device_state["last_seen"] = time.time()

    if pending_command is not None:
        cmd = pending_command
        pending_command = None  # Consume the command

        execution_status["status"] = "DISPATCHED"

        return {
            "has_command": True,
            "id": cmd["id"],
            "command": cmd["command"],
            "summary": cmd["summary"],
            "steps": cmd["steps"],
            "needs_screenshot": cmd.get("needs_screenshot", False),
            "vision_loop": cmd.get("vision_loop", False)
        }

    return {
        "has_command": False
    }


@app.post("/report-status")
def report_status(report: StatusReport):
    global execution_status, device_state
    device_state["connected"] = True
    device_state["last_seen"] = time.time()

    execution_status = {
        "id": report.id,
        "status": report.status,
        "current_step": report.current_step,
        "total_steps": report.total_steps,
        "current_action": report.current_action,
        "current_reason": report.current_reason,
        "error": report.error
    }

    print(f"Device reported execution status for {report.id}: {report.status} (Step {report.current_step}/{report.total_steps})")
    return {"status": "ok"}


@app.get("/execution-status")
def get_execution_status():
    is_connected = (time.time() - device_state["last_seen"]) < 6.0
    return {
        "device": {
            "connected": is_connected,
            "last_seen": device_state["last_seen"]
        },
        "execution": execution_status,
        "messages": message_history,
        "vision": {
            "has_screenshot": latest_screenshot["data"] is not None,
            "screenshot_age_seconds": (
                round(time.time() - latest_screenshot["timestamp"], 1)
                if latest_screenshot["timestamp"] > 0 else None
            )
        },
        "vision_loop": {
            "active": vision_loop_state["active"],
            "loop_id": vision_loop_state["loop_id"],
            "current_step": vision_loop_state["current_step"],
            "status": vision_loop_state["status"],
            "error": vision_loop_state["error"],
            "step_history": [
                {
                    "step": entry["step"],
                    "action": entry["action"],
                }
                for entry in vision_loop_state["step_history"]
            ]
        }
    }


@app.post("/reset-sync")
def reset_sync():
    global pending_command, execution_status, message_history, latest_screenshot
    pending_command = None
    message_history.clear()
    latest_screenshot = {"data": None, "timestamp": 0.0}
    execution_status = {
        "id": "",
        "status": "IDLE",
        "current_step": 0,
        "total_steps": 0,
        "current_action": "",
        "current_reason": "",
        "error": None
    }
    print("Sync states reset.")
    return {"status": "ok"}


@app.post("/reset")
def reset_session():
    """Reset the chat session for a fresh conversation."""
    global chat_session
    chat_session = model.start_chat(history=[])
    reset_vision_loop()
    print("Chat session reset.")
    return {"message": "Session reset successfully"}


# ══════════════════════════════════════════════════════════════════════
#  VISION LOOP ENDPOINTS — Closed-loop step-by-step reactive execution
# ══════════════════════════════════════════════════════════════════════

@app.post("/vision-loop/start")
async def start_vision_loop(request: VisionLoopStart):
    """Start a new vision loop. The phone should begin capturing screenshots."""
    global vision_loop_state, pending_command

    loop_id = str(uuid.uuid4())

    vision_loop_state = {
        "active": True,
        "goal": request.goal,
        "loop_id": loop_id,
        "step_history": [],
        "current_step": 0,
        "max_steps": request.max_steps,
        "status": "WAITING_SCREENSHOT",
        "next_action": None,
        "screenshot_data": None,
        "error": None,
    }

    # Also queue it for the phone via poll-command
    pending_command = {
        "id": loop_id,
        "command": request.goal,
        "summary": f"Vision Loop: {request.goal}",
        "steps": [],
        "needs_screenshot": False,
        "vision_loop": True,  # Flag for phone to enter vision loop mode
    }

    # Add to message history for web UI
    user_msg = {
        "id": loop_id + "-user",
        "command": request.goal,
        "sender": "web",
        "timestamp": time.time()
    }
    message_history.append(user_msg)

    ai_msg = {
        "id": loop_id,
        "command": request.goal,
        "summary": "👁️ Vision Loop active — AI is watching the screen...",
        "steps": [],
        "sender": "gemini",
        "timestamp": time.time() + 0.1,
        "had_screenshot": True,
        "vision_loop": True
    }
    message_history.append(ai_msg)

    print(f"\n{'='*60}")
    print(f"👁️ VISION LOOP STARTED")
    print(f"   Goal: {request.goal}")
    print(f"   Loop ID: {loop_id}")
    print(f"   Max steps: {request.max_steps}")
    print(f"{'='*60}")

    return {
        "loop_id": loop_id,
        "status": "WAITING_SCREENSHOT",
        "goal": request.goal,
        "message": "Vision loop started. Phone should capture and send first screenshot."
    }


@app.post("/vision-loop/screenshot")
async def vision_loop_screenshot(request: VisionLoopScreenshot):
    """Phone sends a screenshot. Backend analyzes it with Gemini Vision
    and returns the SINGLE next action to execute."""
    global vision_loop_state, latest_screenshot

    if not vision_loop_state["active"]:
        return {"error": "No active vision loop", "action": None}

    if request.loop_id != vision_loop_state["loop_id"]:
        return {"error": "Loop ID mismatch", "action": None}

    # Check step limit
    if vision_loop_state["current_step"] >= vision_loop_state["max_steps"]:
        vision_loop_state["status"] = "DONE"
        vision_loop_state["active"] = False
        vision_loop_state["error"] = "Max steps reached"
        return {
            "action": {"action": "done", "reason": f"Maximum {vision_loop_state['max_steps']} steps reached. Stopping."},
            "step": vision_loop_state["current_step"],
            "status": "DONE"
        }

    vision_loop_state["status"] = "THINKING"
    vision_loop_state["screenshot_data"] = request.screenshot

    # Also update the global latest_screenshot for web UI
    latest_screenshot["data"] = request.screenshot
    latest_screenshot["timestamp"] = time.time()

    step_num = vision_loop_state["current_step"] + 1
    print(f"\n👁️ Vision Loop Step {step_num}: Processing screenshot...")

    try:
        # Process screenshot for Gemini
        img_bytes = process_screenshot(request.screenshot)
        if img_bytes is None:
            return {"error": "Screenshot processing failed", "action": None}

        image_part = {
            "mime_type": "image/jpeg",
            "data": img_bytes
        }

        # Build the prompt with goal + history
        prompt_text = build_vision_loop_prompt(
            vision_loop_state["goal"],
            vision_loop_state["step_history"]
        )

        # Call Gemini Vision — single action response
        response = vision_loop_model.generate_content([prompt_text, image_part])
        raw_text = response.text.strip()

        # Parse the single action JSON
        action = parse_gemini_response(raw_text)

        action_type = action.get("action", "unknown")
        action_reason = action.get("reason", "")

        print(f"   🧠 AI decided: [{action_type}] {action_reason}")

        # Check if goal is achieved
        if action_type == "done":
            vision_loop_state["status"] = "DONE"
            vision_loop_state["active"] = False
            vision_loop_state["next_action"] = action

            # Record in history
            vision_loop_state["step_history"].append({
                "step": step_num,
                "action": action,
                "screenshot_timestamp": time.time()
            })

            print(f"   ✅ GOAL ACHIEVED: {action_reason}")

            return {
                "action": action,
                "step": step_num,
                "status": "DONE",
                "message": "Goal achieved!"
            }

        # Store the action for phone to execute
        vision_loop_state["next_action"] = action
        vision_loop_state["current_step"] = step_num
        vision_loop_state["status"] = "WAITING_EXECUTION"

        # Record in history
        vision_loop_state["step_history"].append({
            "step": step_num,
            "action": action,
            "screenshot_timestamp": time.time()
        })

        return {
            "action": action,
            "step": step_num,
            "status": "WAITING_EXECUTION",
            "message": f"Execute this action, then send a new screenshot."
        }

    except json.JSONDecodeError as e:
        print(f"   ❌ JSON parse error: {e}")
        vision_loop_state["status"] = "WAITING_SCREENSHOT"
        return {
            "error": f"AI response parse error: {e}",
            "action": None,
            "retry": True,
            "message": "Send the screenshot again — AI response was malformed."
        }
    except Exception as e:
        print(f"   ❌ Vision loop error: {e}")
        vision_loop_state["status"] = "FAILED"
        vision_loop_state["error"] = str(e)
        vision_loop_state["active"] = False
        return {
            "error": str(e),
            "action": None,
            "status": "FAILED"
        }


@app.post("/vision-loop/action-complete")
async def vision_loop_action_complete(request: VisionLoopActionComplete):
    """Phone reports that it executed the action. Loop goes back to WAITING_SCREENSHOT."""
    global vision_loop_state

    if not vision_loop_state["active"]:
        return {"error": "No active vision loop"}

    if request.loop_id != vision_loop_state["loop_id"]:
        return {"error": "Loop ID mismatch"}

    if request.success:
        vision_loop_state["status"] = "WAITING_SCREENSHOT"
        print(f"   ✅ Step {request.step_index} executed successfully — waiting for next screenshot")
    else:
        vision_loop_state["status"] = "WAITING_SCREENSHOT"  # Still allow retry with new screenshot
        vision_loop_state["error"] = request.error
        print(f"   ⚠️ Step {request.step_index} reported failure: {request.error} — will retry with new screenshot")

    # Update device state
    device_state["connected"] = True
    device_state["last_seen"] = time.time()

    return {
        "status": vision_loop_state["status"],
        "next": "Send next screenshot to continue the loop"
    }


@app.get("/vision-loop/status")
def vision_loop_status():
    """Return the full vision loop state for the web UI."""
    return {
        "active": vision_loop_state["active"],
        "goal": vision_loop_state["goal"],
        "loop_id": vision_loop_state["loop_id"],
        "current_step": vision_loop_state["current_step"],
        "max_steps": vision_loop_state["max_steps"],
        "status": vision_loop_state["status"],
        "next_action": vision_loop_state["next_action"],
        "error": vision_loop_state["error"],
        "step_history": [
            {
                "step": entry["step"],
                "action": entry["action"],
            }
            for entry in vision_loop_state["step_history"]
        ],
        "has_screenshot": vision_loop_state["screenshot_data"] is not None,
    }


@app.post("/vision-loop/abort")
async def abort_vision_loop():
    """Emergency stop — abort the running vision loop."""
    if vision_loop_state["active"]:
        print(f"\n🛑 VISION LOOP ABORTED (was at step {vision_loop_state['current_step']})")
        reset_vision_loop()
        return {"status": "aborted", "message": "Vision loop aborted"}
    return {"status": "no_loop", "message": "No active vision loop to abort"}


@app.get("/vision-loop/screenshot-preview")
def get_screenshot_preview():
    """Return the latest screenshot as base64 for the web UI to display."""
    screenshot = latest_screenshot["data"] or vision_loop_state["screenshot_data"]
    if screenshot:
        return {
            "has_screenshot": True,
            "screenshot": screenshot,
            "step": vision_loop_state["current_step"] if vision_loop_state["active"] else execution_status.get("current_step", 0),
            "timestamp": latest_screenshot["timestamp"]
        }
    return {"has_screenshot": False}
