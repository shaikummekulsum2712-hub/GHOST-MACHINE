from agent.action_schema import ActionResponse


def get_mock_action(
    command: str,
    screenshot_path: str | None = None
) -> ActionResponse:
    """
    Fake brain for testing.

    It ignores real AI for now and always returns a fixed tap action.

    Used to test:
    Android → backend → JSON → Android tap
    """

    reason = "Mock backend action: tap fixed coordinate"

    if screenshot_path:
        reason = f"Mock action after receiving screenshot: {screenshot_path}"

    return ActionResponse(
        action="tap",
        x=500,
        y=900,
        text=None,
        direction=None,
        reason=reason,
        confidence=0.9
    )