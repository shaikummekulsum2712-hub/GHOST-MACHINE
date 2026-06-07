from agent.action_schema import ActionResponse
from agent.llm_client import call_vision_model


def get_next_action(
    command: str,
    screenshot_path: str | None = None,
    screen_elements_json: str | None = None
) -> ActionResponse:
    if screenshot_path:
        return call_vision_model(
            command=command,
            screenshot_path=screenshot_path,
            screen_elements_json=screen_elements_json
        )

    return ActionResponse(
        action="ask_user",
        x=None,
        y=None,
        text=None,
        direction=None,
        element_id=None,
        target_text=None,
        target_description=None,
        reason="Screenshot is required for vision-based control.",
        confidence=1.0
    )