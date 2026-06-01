from fastapi import FastAPI, UploadFile, File, Form
from pydantic import BaseModel
from datetime import datetime
from pathlib import Path

app = FastAPI(title="Ghost Machine Backend")


# folder where screenshots from android will be saved
UPLOAD_DIR = Path("uploads")
UPLOAD_DIR.mkdir(exist_ok=True)


class CommandRequest(BaseModel):
    command: str

@app.get("/")
def home():
    return {
    "message": "Ghost Machine backend is running"
    }

@app.post("/next-action")
def get_next_action(request: CommandRequest):
    print("User command received:", request.command)

    return {
            "action": "tap",
            "x": 500,
            "y": 900,
            "text": None,
            "direction": None,
            "reason": "Mock backend action: tap fixed coordinate",
            "confidence": 0.9
    }

@app.post("/analyze-screen")
async def analyse_screen(command: str = Form(...), screenshot: UploadFile = File(...)):

    print("command received:", command)
    print("screenshot filename:", screenshot.filename)

    # create timestamp-based file filename
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    screenshot_filename = f"screenshot_{timestamp}.png"
    screenshot_path = UPLOAD_DIR / screenshot_filename


    image_bytes = await screenshot.read()


    with open(screenshot_path, "wb") as f:
        f.write(image_bytes)

    print("Screenshot saved at:", screenshot_path)

    return {
        "action": "tap",
        "x": 500,
        "y": 900,
        "text": None,
        "direction": None,
        "reason": "Mock action after receiving screenshot",
        "confidence": 0.9,
        "screenshot_saved": str(screenshot_path)
    }