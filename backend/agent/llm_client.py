from agent.action_schema import ActionResponse


def call_vision_model(
    command: str,
    screenshot_path: str
) -> ActionResponse:
    """
    Placeholder for real VLM call.

    Later this function will:
    - read screenshot image
    - build/send request to NVIDIA NIM / Gemini / OpenAI / Qwen-VL
    - receive model response
    - parse JSON
    - return ActionResponse

    For now, real VLM is not connected.
    """

    raise NotImplementedError("Real VLM is not connected yet.")