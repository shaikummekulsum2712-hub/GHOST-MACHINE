from fastapi import FastAPI
from fastapi.responses import HTMLResponse
from pydantic import BaseModel
import google.generativeai as genai
import json
import os
import uuid
import time
from typing import Optional, List
from dotenv import load_dotenv
from device_profile import get_device_context
from frontend import HTML_CONTENT

# Load environment variables
load_dotenv()

# Configure Gemini
genai.configure(api_key=os.getenv("GEMINI_API_KEY"))

app = FastAPI(title="Ghost Machine AI Backend")

# ── System prompt that teaches Gemini how to be a phone controller ──
SYSTEM_PROMPT = f"""You are Ghost Machine, an AI agent that controls an Android phone through accessibility services.
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

## Common Android Layout Knowledge (for 1260x2800 screen)

### Home Screen / App Drawer
- App grid: apps are typically in a 4-5 column grid
- Home screen apps: bottom row (dock) is around y=2550
- Swipe UP from bottom center to open app drawer: startX=630, startY=2600, endX=630, endY=800
- App drawer search bar: approximately x=630, y=350
- Apps in app drawer are in a scrollable grid, rows spaced ~250px apart starting from y~500

### Calculator App (typical Android/Google Calculator)
- Display area: top portion y=200-700
- Button grid layout (approximate centers, 4 columns):
  - Row 1 (y≈1150): C/AC, parentheses, %, ÷
  - Row 2 (y≈1400): 7, 8, 9, ×
  - Row 3 (y≈1650): 4, 5, 6, −
  - Row 4 (y≈1900): 1, 2, 3, +
  - Row 5 (y≈2150): 0 (wide), ., =
  - Column X positions: ~200, ~440, ~680, ~940
  - The = button and operators are typically on the right side around x=1050

### Navigation
- Back: swipe from left edge (startX=0, startY=1400, endX=400, endY=1400)
- Home: swipe up from very bottom (startX=630, startY=2780, endX=630, endY=2400)
- Recent apps: swipe up from bottom and hold (use longer duration ~500ms)

### Status Bar & Notifications
- Pull down notification shade: swipe from top (startX=630, startY=0, endX=630, endY=1400)
- Quick settings: second swipe down from notification shade

### Settings App
- Settings items are in a scrollable list, each item ~150-200px tall
- Search bar at top: approximately x=630, y=250

## Rules
1. Return ONLY a valid JSON object with two keys: "summary" and "steps"
2. "summary" is a short human-readable explanation of what you'll do (1-2 sentences)
3. "steps" is a JSON array of action objects
4. Use reasonable coordinates based on the device screen size and common layouts
5. Add "wait" actions after opening apps (2000-3000ms) to let them fully load
6. Add "wait" actions after taps that trigger navigation or open new screens (1000-1500ms)
7. IMPORTANT: Always add a "wait" action of at least 1500ms BEFORE any "type" action, to ensure the keyboard and input field are fully ready
8. Break complex tasks into small, individual steps
9. Each step must have a "reason" field explaining what it does
10. If you need to open an app, first go to home screen, then open app drawer, then tap the app
11. For typing, first tap the input field, wait 1500ms for keyboard to appear, then use the "type" action
12. Do NOT include any markdown, explanation, or text outside the JSON object
13. Estimate coordinates as accurately as possible based on the layout knowledge above
14. If the user asks something that cannot be done via phone actions, return a summary explaining why and an empty steps array

## Example

User: "Open calculator and calculate 25 + 13"

Response:
{{
  "summary": "I'll open the Calculator app and compute 25 + 13 for you.",
  "steps": [
    {{"action": "swipe", "startX": 630, "startY": 2600, "endX": 630, "endY": 800, "duration": 300, "reason": "Swipe up to open app drawer"}},
    {{"action": "wait", "duration": 1000, "reason": "Wait for app drawer to open"}},
    {{"action": "tap", "x": 630, "y": 350, "reason": "Tap search bar in app drawer"}},
    {{"action": "wait", "duration": 500, "reason": "Wait for keyboard"}},
    {{"action": "type", "text": "Calculator", "reason": "Search for Calculator app"}},
    {{"action": "wait", "duration": 800, "reason": "Wait for search results"}},
    {{"action": "tap", "x": 200, "y": 500, "reason": "Tap Calculator app from search results"}},
    {{"action": "wait", "duration": 1500, "reason": "Wait for Calculator to open"}},
    {{"action": "tap", "x": 200, "y": 1900, "reason": "Tap digit 2"}},
    {{"action": "tap", "x": 440, "y": 1900, "reason": "Tap digit 5"}},
    {{"action": "tap", "x": 940, "y": 1900, "reason": "Tap + operator"}},
    {{"action": "tap", "x": 200, "y": 1900, "reason": "Tap digit 1"}},
    {{"action": "tap", "x": 440, "y": 1650, "reason": "Tap digit 3"}},
    {{"action": "tap", "x": 1050, "y": 2150, "reason": "Tap = to calculate"}}
  ]
}}"""

# Initialize the Gemini model — try multiple models for free tier availability
MODEL_NAMES = ["gemini-2.5-flash", "gemini-2.0-flash-lite", "gemini-2.0-flash", "gemini-1.5-flash"]

model = None
for model_name in MODEL_NAMES:
    try:
        model = genai.GenerativeModel(
            model_name=model_name,
            system_instruction=SYSTEM_PROMPT
        )
        print(f"Using model: {model_name}")
        break
    except Exception as e:
        print(f"Model {model_name} unavailable: {e}")

if model is None:
    raise RuntimeError("No Gemini model available. Check your API key.")

# Session memory for follow-up commands
chat_session = model.start_chat(history=[])


# Synchronized state between Web Companion UI and Android App
device_state = {
    "connected": False,
    "last_seen": 0.0,
}

pending_command = None

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


class CommandRequest(BaseModel):
    command: str
    sender: str = "web"  # "web" or "android"


class StatusReport(BaseModel):
    id: str
    status: str          # "EXECUTING", "SUCCESS", "FAILED"
    current_step: int
    total_steps: int
    current_action: str = ""
    current_reason: str = ""
    error: Optional[str] = None


@app.get("/", response_class=HTMLResponse)
def home():
    return HTML_CONTENT


@app.post("/next-action")
async def get_next_action(request: CommandRequest):
    print(f"\n{'='*50}")
    print(f"User command from {request.sender}: {request.command}")
    print(f"{'='*50}")

    try:
        # Send to Gemini
        response = chat_session.send_message(request.command)
        raw_text = response.text.strip()

        # Clean up markdown code fences if Gemini wraps the response
        if raw_text.startswith("```"):
            lines = raw_text.split("\n")
            lines = [l for l in lines if not l.strip().startswith("```")]
            raw_text = "\n".join(lines)

        # Parse the JSON response
        result = json.loads(raw_text)
        summary = result.get("summary", "Executing actions...")
        steps = result.get("steps", [])

        print(f"AI Summary: {summary}")
        print(f"Steps: {len(steps)}")
        for i, step in enumerate(steps):
            print(f"  Step {i+1}: {step.get('action')} — {step.get('reason', '')}")

        # Generate unique ID for this execution sequence
        msg_id = str(uuid.uuid4())

        # Save to messages list for browser rendering history
        msg_entry = {
            "id": msg_id,
            "command": request.command,
            "summary": summary,
            "steps": steps,
            "sender": request.sender,
            "timestamp": time.time()
        }
        message_history.append(msg_entry)

        # If submitted from browser, enqueue it for phone execution
        if request.sender == "web":
            global pending_command, execution_status
            pending_command = {
                "id": msg_id,
                "command": request.command,
                "summary": summary,
                "steps": steps
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
            print(f"Queued command for Android device execution (ID: {msg_id})")

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
            "steps": cmd["steps"]
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
        "messages": message_history
    }


@app.post("/reset-sync")
def reset_sync():
    global pending_command, execution_status, message_history
    pending_command = None
    message_history.clear()
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
    print("Chat session reset.")
    return {"message": "Session reset successfully"}
