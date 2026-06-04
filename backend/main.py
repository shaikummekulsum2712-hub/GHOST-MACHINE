from fastapi import FastAPI, UploadFile, File, Form
from datetime import datetime
from pathlib import Path

from agent.agent_loop import get_next_action as agent_get_next_action
from agent.action_schema import CommandRequest, ActionResponse


app = FastAPI(title="Ghost Machine Backend")


# Folder where screenshots from Android will be saved
UPLOAD_DIR = Path("uploads")
UPLOAD_DIR.mkdir(exist_ok=True)


@app.get("/")
def home():
    """
    Health check endpoint.
    Used to confirm backend is running.
    """
    return {
        "message": "Ghost Machine backend is running"
    }


@app.post("/next-action", response_model=ActionResponse)
def next_action(request: CommandRequest):
    """
    Old/simple endpoint.

    Input:
    {
        "command": "search biryani"
    }

    Output:
    Action JSON for Android.
    """
    print("User command received:", request.command)

    return agent_get_next_action(request.command)


@app.post("/analyze-screen", response_model=ActionResponse)
async def analyze_screen(
    command: str = Form(...),
    screenshot: UploadFile = File(...)
):
    """
    Main screenshot endpoint.


    Android sends:
    - command text
    - screenshot image file

    Backend:
    - saves screenshot with timestamp
    - sends command + screenshot path to agent loop
    - returns action JSON
    """
    print("Command received:", command)
    print("Screenshot filename:", screenshot.filename)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    screenshot_filename = f"screenshot_{timestamp}.png"
    screenshot_path = UPLOAD_DIR / screenshot_filename

    image_bytes = await screenshot.read()

    with open(screenshot_path, "wb") as f:
        f.write(image_bytes)

    print("Screenshot saved at:", screenshot_path)

    return agent_get_next_action(command, str(screenshot_path))