from agent.action_schema import ActionResponse


# Ordinary messaging is a requested, reversible communication action and must
# not be confused with a financial transfer.  Each phrase below identifies an
# action that needs the user to take over or explicitly confirm.
RISKY_PHRASES = [
    "send money", "send payment", "make payment", "pay", "payment",
    "upi", "bank transfer", "wire transfer", "transfer money", "purchase",
    "place order", "confirm order", "delete account", "delete all",
    "format device", "password", "one-time password", "otp", "cvv",
    "card number", "pin", "seed phrase", "recovery phrase", "private key",
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

    for phrase in RISKY_PHRASES:
        if phrase in combined_text:
            return ActionResponse(
                action="ask_user",
                x=None,
                y=None,
                text=None,
                direction=None,
                reason=f"Safety check requires user confirmation: {phrase}",
                user_message="I can't complete that sensitive action automatically. Please handle it directly.",
                confidence=1.0,
            )

    return action
