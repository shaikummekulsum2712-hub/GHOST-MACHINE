
import re

from agent.action_schema import ActionResponse


SENSITIVE_PHRASES = (
    "send money",
    "transfer money",
    "bank transfer",
    "wire transfer",
    "confirm payment",
    "make payment",
    "pay now",
    "buy now",
    "purchase",
    "delete account",
    "close account",
    "reset password",
    "change password",
    "recovery phrase",
    "seed phrase",
    "private key",
    "one time password",
    "otp",
    "cvv",
    "card number",
)


def _normalise(value: str) -> str:
    return re.sub(r"\s+", " ", value.lower()).strip()


def _contains_sensitive_phrase(value: str) -> str | None:
    normalised = _normalise(value)

    for phrase in SENSITIVE_PHRASES:
        if phrase in normalised:
            return phrase

    return None


def apply_safety_filter(
    action: ActionResponse,
    command: str = ""
) -> ActionResponse:
    """
    Blocks high-impact financial, security, account, and destructive actions.

    Ordinary communication commands such as:
        "send him hello"
        "message her good morning"

    are not blocked merely because they contain "send".
    """

    combined_text = " ".join(
        [
            command,
            action.reason or "",
            action.text or "",
            action.target_text or "",
            action.target_description or "",
        ]
    )

    matched_phrase = _contains_sensitive_phrase(combined_text)

    if matched_phrase is not None:
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
            reason=f"Sensitive action requires confirmation: {matched_phrase}",
            user_message=(
                "This action may affect money, security, or your account. "
                "Please confirm before I continue."
            ),
            confidence=1.0,
        )

    return action