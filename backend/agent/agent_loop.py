from agent.action_schema import ActionResponse
from agent.llm_client import call_vision_model


def get_next_action(
    command: str,
    screenshot_path: str | None = None,
    screen_elements_json: str | None = None,
    parsed_intent: str | None = None,
    parsed_target: str | None = None,
    android_uncertainty: str | None = None,
    previous_action: str | None = None
) -> ActionResponse:
    if not screenshot_path:
        return ActionResponse(
            action="ask_user",
            element_id=None,
            grid_cell=None,
            x=None,
            y=None,
            text=None,
            direction=None,
            target_text=None,
            target_description=None,
            reason="Screenshot required.",
            confidence=1.0
        )

    return call_vision_model(
        command=command,
        screenshot_path=screenshot_path,
        screen_elements_json=screen_elements_json,
        parsed_intent=parsed_intent,
        parsed_target=parsed_target,
        android_uncertainty=android_uncertainty,
        previous_action=previous_action
    )