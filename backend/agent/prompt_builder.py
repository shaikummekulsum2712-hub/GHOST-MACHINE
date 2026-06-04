from agent.action_schema import ActionResponse


def build_vision_prompt(
    command: str,
    previous_action: ActionResponse | None = None
) -> str:
    """
    Builds the instruction prompt for the vision model.

    The VLM will receive:
    - this prompt
    - the current phone screenshot

    It should return exactly one next action as JSON.
    """

    previous_action_text = "None"

    if previous_action:
        previous_action_text = previous_action.model_dump_json()

    prompt = f"""
You are the brain of Ghost Machine, a vision-based Android agent.

User command:
{command}

Previous action:
{previous_action_text}

Your job:
You are an Android phone-control agent.

Allowed actions:
-tap = visible UI target
-swipe = target not visible / previous chat / scroll needed
-type = only when user clearly wants text entered
-wait = loading
-done = goal already completed
-ask_user = risky/unclear

Return only valid JSON in this format:
{{
  "action": "tap | swipe | type | wait | done | ask_user",
  "x": number or null,
  "y": number or null,
  "text": string or null,
  "direction": "up | down | left | right" or null,
  "reason": "short reason",
  "confidence": number between 0 and 1
}}
  """

    return prompt.strip()