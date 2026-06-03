from typing import Annotated, Literal, Optional
from pydantic import BaseModel, Field


class CommandRequest(BaseModel):
    """
    Request model for command-only endpoint.

    Used by:
    POST /next-action
    """

    command: Annotated[
        str,
        Field(
            min_length=1,
            max_length=300,
            description="User's task command, for example: search biryani"
        )
    ]


class ActionResponse(BaseModel):
    """
    Standard action JSON returned by backend to Android.

    Android/Kotlin depends on these exact keys:
    - action
    - x
    - y
    - text
    - direction
    - reason
    - confidence
    """

    action: Annotated[
        Literal["tap", "swipe", "type", "wait", "done", "ask_user"],
        Field(description="The next action Android should perform")
    ]

    x: Annotated[
        Optional[float],
        Field(ge=0, description="X coordinate for tap action")
    ] = None

    y: Annotated[
        Optional[float],
        Field(ge=0, description="Y coordinate for tap action")
    ] = None

    text: Annotated[
        Optional[str],
        Field(max_length=500, description="Text to type when action is type")
    ] = None

    direction: Annotated[
        Optional[Literal["up", "down", "left", "right"]],
        Field(description="Swipe direction when action is swipe")
    ] = None

    reason: Annotated[
        str,
        Field(
            min_length=1,
            max_length=500,
            description="Short explanation of why this action was chosen"
        )
    ]

    confidence: Annotated[
        float,
        Field(
            ge=0.0,
            le=1.0,
            description="Confidence score between 0 and 1"
        )
    ]