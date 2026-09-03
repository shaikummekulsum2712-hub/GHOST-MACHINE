from fastapi import FastAPI, UploadFile, File, Form
from fastapi.responses import FileResponse
from datetime import datetime
from pathlib import Path
import json

from agent.agent_loop import get_next_action as agent_get_next_action
from agent.action_schema import CommandRequest, ActionResponse
from agent.safety_filter import apply_safety_filter


from agent.planner import plan_command, PlanResponse
from pydantic import BaseModel

class PlanRequest(BaseModel):
    command: str
    reply_language: str = "english"

CONFIDENCE_FLOOR = 0.55  # tune once you have real logs of model confidence

app = FastAPI(title="Ghost Machine Backend")


UPLOAD_DIR = Path("uploads")
UPLOAD_DIR.mkdir(exist_ok=True)

DASHBOARD_FILE = Path("dashboard.html")


latest_status = {
    "status": "idle",
    "command": None,
    "screenshot_file": None,
    "screenshot_url": None,
    "elements_count": 0,
    "action": None,
    "element_id": None,
    "target_text": None,
    "target_description": None,
    "reason": None,
    "confidence": None,
    "error": None,
    "updated_at": None,
}

# Add near your existing status state
status_history: list[dict] = []
MAX_HISTORY = 20

def update_status(**kwargs):
    global status_history
    kwargs["updated_at"] = datetime.now().isoformat()

    # Start a fresh timeline whenever a genuinely new command comes in
    is_new_command = (
        not status_history or
        status_history[-1].get("command") != kwargs.get("command")
    )
    if is_new_command:
        status_history = []

    status_history.append(kwargs)
    status_history = status_history[-MAX_HISTORY:]

    latest_status.update(kwargs)
    latest_status["updated_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")


@app.get("/")

def home():
    return {
        "message": "Ghost Machine backend is running",
        "dashboard": "/dashboard",
        "docs": "/docs"
    }


@app.get("/dashboard")
def dashboard():
    if not DASHBOARD_FILE.exists():
        return {"error": "dashboard.html not found"}

    return FileResponse(DASHBOARD_FILE)


@app.get("/status")
def status():
    return latest_status

@app.get("/status-history")
async def get_status_history():
    return {"steps": status_history}

@app.get("/uploads/{filename}")
def get_uploaded_file(filename: str):
    # Reject path traversal attempts / nested paths - only allow a bare filename
    if "/" in filename or "\\" in filename or filename in (".", ".."):
        return {"error": "invalid filename"}

    file_path = (UPLOAD_DIR / filename).resolve()

    if UPLOAD_DIR.resolve() not in file_path.parents:
        return {"error": "invalid filename"}

    if not file_path.exists():
        return {"error": "file not found"}

    return FileResponse(file_path)


@app.post("/plan-command", response_model=PlanResponse)
async def plan_command_endpoint(req: PlanRequest):
    return plan_command(req.command, req.reply_language)


@app.post("/next-action", response_model=ActionResponse)
def next_action(request: CommandRequest):
    return agent_get_next_action(request.command)


@app.post("/analyze-screen")
async def analyze_screen(
    command: str = Form(...),
    screenshot: UploadFile = File(...),
    screen_elements_json: str | None = Form(None),
    parsed_intent: str | None = Form(None),
    parsed_target: str | None = Form(None),
    android_uncertainty: str | None = Form(None),
    previous_action: str | None = Form(None),
    reply_language: str | None = Form(None)
):
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    suffix = Path(screenshot.filename or "screen.jpg").suffix or ".jpg"
    filename = f"screenshot_{timestamp}{suffix}"
    screenshot_path = UPLOAD_DIR / filename

    contents = await screenshot.read()
    screenshot_path.write_bytes(contents)

    elements_count = 0
    if screen_elements_json:
        try:
            elements_count = len(json.loads(screen_elements_json))
        except Exception:
            elements_count = 0

    print("Command received:", command)
    print("Screenshot filename:", screenshot.filename)
    print("Screenshot saved at:", screenshot_path)
    print("Screen elements count:", elements_count)
    print("Parsed intent:", parsed_intent)
    print("Parsed target:", parsed_target)
    print("Android uncertainty:", android_uncertainty)

    try:
            action = agent_get_next_action(
                command=command,
                screenshot_path=str(screenshot_path),
                screen_elements_json=screen_elements_json,
                parsed_intent=parsed_intent,
                parsed_target=parsed_target,
                android_uncertainty=android_uncertainty,
                previous_action=previous_action,
                reply_language=reply_language
            )
    except Exception as e:
        update_status(
            status="error",
            command=command,
            screenshot_file=filename,
            screenshot_url=f"/uploads/{filename}",
            elements_count=elements_count,
            parsed_intent=parsed_intent,
            parsed_target=parsed_target,
            android_uncertainty=android_uncertainty,
            error=str(e),
        )
        raise

    # --- Safety filter: deterministic keyword block, runs first, always ---
    action = apply_safety_filter(action, command=command)

    # --- Confidence floor: downgrade uncertain non-blocked actions to ask_user ---
    if action.action != "ask_user" and action.confidence < CONFIDENCE_FLOOR:
        action = action.model_copy(update={
            "action": "ask_user",
            "user_message": action.user_message or "I'm not fully sure — can you clarify?",
            "reason": f"{action.reason} (confidence {action.confidence:.2f} below floor)",
        })

    update_status(
        status="success",
        command=command,
        screenshot_file=filename,
        screenshot_url=f"/uploads/{filename}",
        elements_count=elements_count,
        parsed_intent=parsed_intent,
        parsed_target=parsed_target,
        android_uncertainty=android_uncertainty,
        action=action.action,
        element_id=action.element_id,
        grid_cell=action.grid_cell,
        x=action.x,
        y=action.y,
        text=action.text,
        direction=action.direction,
        target_text=action.target_text,
        target_description=action.target_description,
        reason=action.reason,
        confidence=action.confidence,
        reply_language=reply_language,
        user_message=action.user_message,
        error=None,
    )

    return action