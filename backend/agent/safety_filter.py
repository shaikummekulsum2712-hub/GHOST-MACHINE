from agent.action_schema import ActionResponse


RISKY_KEYWORDS = [
    "pay",
    "payment",
    "send",
    "delete",
    "confirm",
    "submit",
    "order",
    "bank",
    "upi",
    "transfer",
    "purchase",
]


def apply_safety_filter(
    action: ActionResponse,
    command: str = ""
) -> ActionResponse:
    """
    Checks if an action is risky before Android executes it.

    It checks:
    - user command
    - action reason
    - action text

    If risky words are found, it returns ask_user.
    """

    combined_text = f"{command} {action.reason} {action.text or ''}".lower()

    for keyword in RISKY_KEYWORDS:
        if keyword in combined_text:
            return ActionResponse(
                action="ask_user",
                x=None,
                y=None,
                text=None,
                direction=None,
                reason=f"Risky action blocked because command/action mentioned: {keyword}",
                confidence=1.0
            )

    return action