from pydantic import BaseModel
from typing import Literal


class CommandRequest(BaseModel):
    command: str


class ActionResponse(BaseModel):
    action: Literal["tap", "swipe", "type", "wait", "done", "ask_user"]

    element_id: int | None = None
    grid_cell: str | None = None

    x: float | None = None
    y: float | None = None

    text: str | None = None
    direction: Literal["up", "down", "left", "right"] | None = None

    target_text: str | None = None
    target_description: str | None = None

    reason: str
    user_message: str | None = None
    confidence: float