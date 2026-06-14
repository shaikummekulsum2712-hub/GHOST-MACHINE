from fastapi import FastAPI, UploadFile, File, Form
from fastapi.responses import FileResponse
from datetime import datetime
from pathlib import Path
import json

from agent.agent_loop import get_next_action as agent_get_next_action
from agent.action_schema import CommandRequest, ActionResponse


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


def update_status(**kwargs):
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


@app.get("/uploads/{filename}")
def get_uploaded_file(filename: str):
    file_path = UPLOAD_DIR / filename

    if not file_path.exists():
        return {"error": "file not found"}

    return FileResponse(file_path)


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
    previous_action: str | None = Form(None)
):
    global latest_status

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

    action = agent_get_next_action(
        command=command,
        screenshot_path=str(screenshot_path),
        screen_elements_json=screen_elements_json,
        parsed_intent=parsed_intent,
        parsed_target=parsed_target,
        android_uncertainty=android_uncertainty,
        previous_action=previous_action
    )

    latest_status = {
        "command": command,
        "screenshot_url": f"/uploads/{filename}",
        "elements_count": elements_count,
        "parsed_intent": parsed_intent,
        "parsed_target": parsed_target,
        "android_uncertainty": android_uncertainty,
        "action": action.action,
        "element_id": action.element_id,
        "grid_cell": action.grid_cell,
        "x": action.x,
        "y": action.y,
        "text": action.text,
        "direction": action.direction,
        "target_text": action.target_text,
        "target_description": action.target_description,
        "reason": action.reason,
        "confidence": action.confidence,
    }

    return action