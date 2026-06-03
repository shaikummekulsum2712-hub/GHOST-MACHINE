from agent.mock_agent import get_mock_action
from agent.action_schema import ActionResponse
from agent.safety_filter import apply_safety_filter


def get_next_action(
    command: str,
    screenshot_path: str | None = None
) -> ActionResponse:
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

    action = get_mock_action(command, screenshot_path)
    safe_action = apply_safety_filter(action, command)

    return safe_action