from pydantic import BaseModel
from typing import Literal


class CommandRequest(BaseModel):
    command: str


class ActionResponse(BaseModel):
    action: Literal["tap", "swipe", "type", "wait", "done", "ask_user"]

    # fallback coordinates from VLM
    x: float | None = None
    y: float | None = None

    # for typing
    text: str | None = None

    # for swiping
    direction: Literal["up", "down", "left", "right"] | None = None

    # best new field:
    # Android sends visible UI elements with ids,
    # VLM chooses the element_id to tap.
    element_id: int | None = None

    # optional backup hints
    target_text: str | None = None
    target_description: str | None = None

    reason: str
    confidence: float