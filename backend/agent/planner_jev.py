import json
import os
import requests
import laya
from pydantic import BaseModel
from dotenv import load_dotenv

load_dotenv()

ALLOWED_INTENTS = {
    "open",
    "open_chat",
    "call",
    "search",
    "type",
    "type_and_send",
    "send",
    "tap",
    "scroll",
    "back",
    "home",
    "video_call",
    "wait",
}

MAX_PLAN_OPTIONS = 10

OLLAMA_BASE_URL = os.getenv(
    "OLLAMA_BASE_URL",
    "http://127.0.0.1:11434",
)

OPTION_MODEL = os.getenv(
    "OPTION_MODEL",
    "qwen2.5:0.5b",
)

OPTION_MODEL_TIMEOUT = int(
    os.getenv("OPTION_MODEL_TIMEOUT", "120")
)

LAYA_MODEL = os.getenv(
    "LAYA_MODEL",
    "convaiinnovations/laya",
)

MIN_LAYA_CONFIDENCE = 0.55
_LAYA_AGENT = None


class PlannedStep(BaseModel):
    intent: str
    target: str


class PlanResponse(BaseModel):
    steps: list[PlannedStep]


class MacroOption(BaseModel):
    key: str
    description: str
    intent: str
    target: str


class MacroPlan(BaseModel):
    goal: str
    options: list[MacroOption]


def _clean_json(text: str) -> dict:
    text = text.strip()

    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    start = text.find("{")
    end = text.rfind("}")

    if start == -1 or end == -1:
        raise ValueError("No JSON object found")

    return json.loads(text[start:end + 1])


def _call_planner_llm(prompt: str) -> str:
    response = requests.post(
        f"{OLLAMA_BASE_URL.rstrip('/')}/api/chat",
        json={
            "model": OPTION_MODEL,
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "You are Ghost Machine's macro planner. "
                        "Generate candidate actions for the user's task. "
                        "Do not choose the next action. "
                        "Do not execute anything. "
                        "Return JSON only."
                    ),
                },
                {
                    "role": "user",
                    "content": prompt,
                },
            ],
            "stream": False,
            "format": "json",
            "options": {
                "temperature": 0,
            },
        },
        timeout=OPTION_MODEL_TIMEOUT,
    )

    response.raise_for_status()

    data = response.json()

    return data["message"]["content"]


def build_macro_planner_prompt(
    command: str,
    reply_language: str,
    current_state: str = "",
    last_action: str | None = None,
) -> str:
    return f"""
User command:
"{command}"

Reply language:
{reply_language}

Current Android state:
{current_state or "unknown"}

Last executed action:
{last_action or "none"}

Create a DYNAMIC candidate-action set for this exact Android task.

Another decision model called Laya will choose ONE immediate action
from these candidates after considering the current phone state.

Allowed execution intents:
open
open_chat
call
search
type
type_and_send
send
tap
scroll
back
home
video_call
wait

Control intents:
task_finished
ask_user

Rules:

- Do not hardcode application package names.
- Preserve exact app names.
- Preserve exact contact names.
- Preserve exact search queries.
- Preserve exact message text.
- Do not remove words such as "swipe", "open", or "go" when they
  are part of an entity name.
- Create only actions plausibly related to the user's command.
- Do not choose the action yet.
- Do not claim an action succeeded.
- Do not use screen coordinates.
- Do not invent resource IDs.
- Do not assume a specific UI layout.
- Candidate actions should be high-level intents.
- Normally create 3-10 candidates when the task is genuinely
  compound.
- Do not create unnecessary candidates for a simple task.
- Return ONLY JSON.

Format:

{{
  "goal": "short goal",
  "options": [
    {{
      "key": "open_target_app",
      "description": "Open the requested app",
      "intent": "open",
      "target": "YouTube"
    }}
  ]
}}
"""


def generate_macro_plan(
    command: str,
    reply_language: str,
    current_state: str = "",
    last_action: str | None = None,
) -> MacroPlan:
    prompt = build_macro_planner_prompt(
        command,
        reply_language,
        current_state,
        last_action,
    )

    for attempt in range(2):
        try:
            raw = _call_planner_llm(
                prompt
                if attempt == 0
                else prompt + "\nReturn ONLY valid JSON."
            )

            data = _clean_json(raw)

            options = []
            seen = set()

            for item in data.get("options", []):
                key = str(item.get("key", "")).strip()
                description = str(
                    item.get("description", "")
                ).strip()
                intent = str(
                    item.get("intent", "")
                ).strip()
                target = str(
                    item.get("target", "")
                ).strip()

                if not key:
                    continue

                if key in seen:
                    continue

                if not description:
                    continue

                if (
                    intent not in ALLOWED_INTENTS
                    and intent not in {
                        "task_finished",
                        "ask_user",
                    }
                ):
                    continue

                options.append(
                    MacroOption(
                        key=key,
                        description=description,
                        intent=intent,
                        target=target,
                    )
                )

                seen.add(key)

                if len(options) >= MAX_PLAN_OPTIONS:
                    break

            if options:
                return MacroPlan(
                    goal=str(
                        data.get("goal", command)
                    ).strip() or command,
                    options=options,
                )

        except Exception as exc:
            print(
                f"Macro planner failed "
                f"(attempt {attempt + 1}): {exc}"
            )

    return MacroPlan(
        goal=command,
        options=[],
    )


def _get_laya_agent():
    global _LAYA_AGENT

    if _LAYA_AGENT is None:
        print("[LAYA] Loading local Laya model...")
        _LAYA_AGENT = laya.load(LAYA_MODEL)
        print("[LAYA] Model loaded")

    return _LAYA_AGENT


def preload_laya():
    """Load the Laya checkpoint during backend startup, not mid-command."""
    try:
        _get_laya_agent()
        return True
    except Exception as exc:
        print(f"[LAYA] Preload failed: {exc}")
        return False

if os.getenv("PRELOAD_LAYA", "1") == "1":
    preload_laya()


def build_laya_state(
    command: str,
    current_state: str,
    last_action: str | None,
    macro_plan: MacroPlan,
) -> dict:
    return {
        "user_command": command,
        "current_android_state": current_state or "unknown",
        "last_executed_action": last_action or "none",
        "goal": macro_plan.goal,
        "candidate_actions": [
            {
                "key": option.key,
                "description": option.description,
                "intent": option.intent,
                "target": option.target,
            }
            for option in macro_plan.options
        ],
    }


def route_with_laya(
    command: str,
    current_state: str,
    macro_plan: MacroPlan,
    last_action: str | None = None,
) -> MacroOption | None:
    if not macro_plan.options:
        return None

    agent = _get_laya_agent()

    criteria = {}

    for option in macro_plan.options:
        criteria[option.key] = (
            f"{option.description}; "
            f"intent={option.intent}; "
            f"target={option.target}"
        )

    state = build_laya_state(
        command=command,
        current_state=current_state,
        last_action=last_action,
        macro_plan=macro_plan,
    )

    questions = {
        "next_immediate_action": {
            "type": "choice",
            "instructions": (
                "Choose exactly ONE candidate action that should "
                "be executed next from the current Android state. "
                "Use the current state and the global user goal. "
                "Do not choose an action merely because it appears "
                "first. Choose task_finished only when the goal is "
                "already visibly satisfied. Choose ask_user only "
                "when the available candidates cannot safely or "
                "reliably continue."
            ),
            "criteria": criteria,
        }
    }

    result = agent.predict(
        state,
        questions,
    )

    answers = result.get("answers", {})

    answer = answers.get(
        "next_immediate_action",
        {},
    )

    selected_key = answer.get("choice")

    confidence = answer.get(
        "confidence",
        0.0,
    )

    print(
        f"[LAYA] choice={selected_key} "
        f"confidence={confidence}"
    )

    try:
        confidence_value = float(confidence)
    except (TypeError, ValueError):
        confidence_value = 0.0

    if confidence_value < MIN_LAYA_CONFIDENCE:
        print(f"[LAYA] Rejecting low-confidence choice: {confidence_value}")
        return None

    if not selected_key:
        print(
            f"[LAYA] No choice returned: {result}"
        )
        return None

    selected = next(
        (
            option
            for option in macro_plan.options
            if option.key == selected_key
        ),
        None,
    )

    if selected is None:
        print(
            f"[LAYA] Unknown choice: {selected_key}"
        )
        return None

    return selected


def plan_command(
    command: str,
    reply_language: str,
    current_state: str = "",
    last_action: str | None = None,
) -> PlanResponse:
    """
    Backend entry point used by Ghost Machine.

    Qwen3 generates candidate actions.
    Laya selects ONE immediate action.
    Android executes that action and later provides the
    newly observed state for the next planning round.
    """

    macro_plan = generate_macro_plan(
        command=command,
        reply_language=reply_language,
        current_state=current_state,
        last_action=last_action,
    )

    if not macro_plan.options:
        return PlanResponse(
            steps=[]
        )

    selected = route_with_laya(
        command=command,
        current_state=current_state,
        macro_plan=macro_plan,
        last_action=last_action,
    )

    if selected is None:
        return PlanResponse(
            steps=[]
        )

    return PlanResponse(
        steps=[
            PlannedStep(
                intent=selected.intent,
                target=selected.target,
            )
        ]
    )


if __name__ == "__main__":
    result = plan_command(
        command="open YouTube and search Python tutorial",
        reply_language="English",
        current_state="Android home screen",
        last_action=None,
    )

    print(
        json.dumps(
            result.model_dump(),
            indent=2,
            ensure_ascii=False,
        )
    )