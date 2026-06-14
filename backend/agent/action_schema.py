from pydantic import BaseModel
from typing import Literal


class CommandRequest(BaseModel):
    command: str


class ActionResponse(BaseModel):
    action: Literal["tap", "swipe", "type", "wait", "done", "ask_user"]

    # Best target
    element_id: int | None = None

    # Visual fallback target
    grid_cell: str | None = None

    # Last fallback target
    x: float | None = None
    y: float | None = None

    # Extra action data
    text: str | None = None
    direction: Literal["up", "down", "left", "right"] | None = None

    # Human/debug info
    target_text: str | None = None
    target_description: str | None = None
    reason: str
    confidence: float