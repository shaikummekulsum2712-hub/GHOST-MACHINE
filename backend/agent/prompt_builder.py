def build_vision_prompt(
    command: str,
    screen_elements_json: str | None = None,
    parsed_intent: str | None = None,
    parsed_target: str | None = None,
    android_uncertainty: str | None = None,
    previous_action: str | None = None,
    reply_language: str | None = None
) -> str:
    elements_text = screen_elements_json or "[]"
    language = "english"

    prompt = f"""
/no_think

You are selecting ONE next Android UI action.

Work through the decision procedure below in order.
Do not restate the UI elements.
Do not explain your reasoning.
Return only the final JSON object.

User command:
{command}

Parsed intent:
{parsed_intent or "unknown"}

Parsed target:
{parsed_target or "unknown"}

Reply language:
{language}

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
A1 is top-left.
J10 is bottom-right.

Decision procedure - apply in order:

1. LITERAL MATCH

If the target corresponds to visible text or content description,
prefer the element whose text or description most directly matches
the target.

2. FUNCTIONAL MATCH

If the command describes a UI function rather than visible text,
look for the appropriate control.

Examples:
- flip/switch camera → camera switch control
- mute/silence → microphone or mute control
- close/dismiss → close or dismiss control
- back → back control
- search → search field or search control
- send → send control when the Android executor has already determined
  that sending is allowed

Prefer a small relevant control over unrelated large text or
preview/status elements.

3. NAME/FUZZY MATCH

When looking for a person's name and there is no exact match,
allow a reasonable partial or phonetic match.

Do not make a wild guess between unrelated names.

4. NOT FOUND

If no plausible target or functional control exists on the current
screen, return ask_user.

Do not select a random element merely to produce an action.

If this is a retry after a previous failed attempt, use the current
screen and previous action to choose a different or better candidate
when one exists.

If genuinely uncertain between two candidates, prefer the candidate
that is:
- clickable when interaction is required
- semantically closer to the command
- a specific control rather than a status or preview element

Rules:

1. Prefer element_id from the UI elements.
2. If no suitable element_id exists, use grid_cell.
3. Use x/y only as a final fallback.
4. Return exactly ONE action.
5. For entering text, use action "type".
6. Do not independently authorize payments, transfers, credential entry,
   OTP entry, PIN entry, account deletion, or other protected operations.
7. If the action cannot be selected safely or reliably, use ask_user.
8. user_message must be in English.
9. reason must be fewer than 8 words.
10. Return valid raw JSON only.
11. No markdown.
12. No explanation.
13. target_text is REQUIRED whenever action is "tap".
14. Do not describe your reasoning in the output.
15. Never invent element IDs, resource IDs, coordinates, visible text,
    contacts, applications, or UI controls.
16. Do not claim that an action succeeded. You only select the next action.
17. Do not return "done" unless the Android screen provides evidence
    that the requested goal is visibly complete.

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
  "user_message": string|null,
  "confidence": number
}}
"""

    return prompt.strip()