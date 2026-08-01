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
    language = reply_language or "english"

    prompt = f"""
/no_think

You are selecting ONE next Android action. Work through the steps below in
order and stop at the first one that applies. Do not restate the UI elements
list back to yourself - just apply the steps silently and output the result.

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
Example: A1 is top-left, J10 is bottom-right.

Decision procedure - apply in order:
1. LITERAL MATCH: Does any element's text or description contain the exact
   words from the target? If yes, use that element.
2. FUNCTIONAL MATCH: If the command describes an action rather than naming
   visible text (e.g. "flip the camera", "mute the call", "close this"),
   the target element usually will NOT contain those exact words. Instead
   search descriptions for functional synonyms: flip/switch/toggle/rotate
   for camera or mode controls, mute/silence for audio, close/dismiss/back
   for closing, etc. Prefer small icon-only elements near the top or bottom
   of the screen for this kind of control - they are rarely labeled with
   the same word the user said.
3. NAME/FUZZY MATCH: If searching for a person's name and no element
   matches exactly, check for a partial or phonetically similar match
   (e.g. "aashi" could match "Aashi", "Ashi", "Asiya" - use judgement, but
   do not guess wildly between unrelated names).
4. NOT FOUND: If after steps 1-3 nothing plausible exists on this screen,
   output action "ask_user" - do not pick a random element or the closest
   textual coincidence just to produce an answer.

If this is a retry after a previous attempt failed to decide: commit to
your single best candidate now rather than re-deriving the same comparison
again. If genuinely torn between two elements, pick the one that is
clickable and prefer smaller icon controls over large text labels or
preview/status elements for functional commands like "flip camera".

Rules:
1. Prefer element_id from UI elements.
2. If no element_id matches, use grid_cell.
3. Use x/y only as last fallback.
4. Return only ONE action.
5. For entering text, use action "type".
6. For risky actions like send, pay, delete, confirm, submit, use ask_user.
7. If action is ask_user, user_message must be in the reply language.
8. If reply_language is hinglish, user_message should be casual Hinglish.
9. If reply_language is telugu, user_message should be simple roman Telugu.
10. reason must be less than 8 words.
11. Return valid raw JSON only.
12. No markdown. No explanation.
13. target_text is REQUIRED whenever action is "tap". Always include it.
14. Do not describe your reasoning in the output - only the final JSON.

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