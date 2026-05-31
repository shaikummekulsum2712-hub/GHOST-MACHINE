from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="Ghost Machine Backend")

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
