from agent.mock_agent import get_mock_action
from agent.action_schema import ActionResponse
from agent.safety_filter import apply_safety_filter
from agent.llm_client import call_vision_model

def get_next_action(command: str,screenshot_path: str | None = None) -> ActionResponse:
    """
    Main brain controller for Ghost Machine.

    Current flow:
    - get fake action from mock_agent
    - apply safety filter
    - return final safe action

    Later flow:
    - build prompt
    - call VLM
    - validate response
    - apply safety filter
    - return real action
    """

    if screenshot_path:
        action = call_vision_model(command, screenshot_path)
    else:
        action = get_mock_action(command)

    safe_action = apply_safety_filter(action, command)

    return safe_action

    return safe_action