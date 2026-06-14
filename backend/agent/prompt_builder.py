def build_vision_prompt(
    command: str,
    screen_elements_json: str | None = None,
    parsed_intent: str | None = None,
    parsed_target: str | None = None,
    android_uncertainty: str | None = None,
    previous_action: str | None = None
) -> str:
    elements_text = screen_elements_json or "[]"

    return f"""
/no_think

You are selecting ONE next Android action.

User command:
{command}

Parsed intent:
{parsed_intent or "unknown"}

Parsed target:
{parsed_target or "unknown"}

Android uncertainty:
{android_uncertainty or "Android could not confidently choose an element."}

Previous action:
{previous_action or "none"}

UI elements:
{elements_text}

Element format:
i = element id
t = visible text
d = content description
b = bounds [left, top, right, bottom]
c = clickable, 1 or 0
e = editable, 1 or 0

Screenshot:
Provided image.

Grid:
The screenshot is divided into 10 columns A-J and 10 rows 1-10.
Use grid_cell only if no element_id is suitable.
Example: A1 is top-left, J10 is bottom-right.

Rules:
1. Prefer element_id from UI elements.
2. If no element_id matches, use grid_cell.
3. Use x/y only as last fallback.
4. Return only ONE action.
5. For entering text, use action "type".
6. For risky actions like send, pay, delete, confirm, submit, use ask_user.
7. Return done only if the user goal is already achieved.
8. reason must be less than 8 words.
9. Return valid raw JSON only.
10. No markdown. No explanation.

JSON format:
{{
  "action": "tap|type|swipe|wait|done|ask_user",
  "element_id": number|null,
  "grid_cell": "A1-J10"|null,
  "x": number|null,
  "y": number|null,
  "text": string|null,
  "direction": "up|down|left|right"|null,
  "target_text": string|null,
  "target_description": string|null,
  "reason": "short reason",
  "confidence": number
}}
"""

    return prompt.strip()