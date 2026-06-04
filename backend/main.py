from fastapi import FastAPI, UploadFile, File, Form
from fastapi.responses import FileResponse
from datetime import datetime
from pathlib import Path

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
    "action": None,
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
        return {
            "error": "dashboard.html not found"
        }

    return FileResponse(DASHBOARD_FILE)


@app.get("/status")
def status():
    return latest_status


@app.get("/uploads/{filename}")
def get_uploaded_file(filename: str):
    file_path = UPLOAD_DIR / filename

    if not file_path.exists():
        return {
            "error": "file not found"
        }

    return FileResponse(file_path)


@app.post("/next-action", response_model=ActionResponse)
def next_action(request: CommandRequest):
    print("User command received:", request.command)

    update_status(
        status="command_received",
        command=request.command,
        screenshot_file=None,
        screenshot_url=None,
        action=None,
        reason=None,
        confidence=None,
        error=None,
    )

    action = agent_get_next_action(request.command)

    update_status(
        status="action_generated",
        action=action.action,
        reason=action.reason,
        confidence=action.confidence,
    )

    return action


@app.post("/analyze-screen", response_model=ActionResponse)
async def analyze_screen(
    command: str = Form(...),
    screenshot: UploadFile = File(...)
):
    print("Command received:", command)
    print("Screenshot filename:", screenshot.filename)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    screenshot_filename = f"screenshot_{timestamp}.jpg"
    screenshot_path = UPLOAD_DIR / screenshot_filename

    update_status(
        status="screenshot_received",
        command=command,
        screenshot_file=screenshot_filename,
        screenshot_url=f"/uploads/{screenshot_filename}",
        action=None,
        reason=None,
        confidence=None,
        error=None,
    )

    image_bytes = await screenshot.read()

    with open(screenshot_path, "wb") as f:
        f.write(image_bytes)

    print("Screenshot saved at:", screenshot_path)

    update_status(status="analyzing_with_vlm")

    try:
        action = agent_get_next_action(command, str(screenshot_path))

        update_status(
            status="action_generated",
            action=action.action,
            reason=action.reason,
            confidence=action.confidence,
            error=None,
        )

        return action

    except Exception as e:
        print("Analyze screen failed:", e)

        update_status(
            status="failed",
            error=str(e),
            action="ask_user",
            reason="Backend failed while analyzing screen.",
            confidence=1.0,
        )

        return ActionResponse(
            action="ask_user",
            x=None,
            y=None,
            text=None,
            direction=None,
            reason="Backend failed while analyzing screen.",
            confidence=1.0
        )