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
Look at the phone screenshot and decide exactly ONE next action.

Allowed actions:
- tap
- swipe
- type
- wait
- done
- ask_user

Return only valid JSON in this format:
{{
  "action": "tap | swipe | type | wait | done | ask_user",
  "x": number or null,
  "y": number or null,
  "text": string or null,
  "direction": "