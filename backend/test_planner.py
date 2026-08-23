from agent.planner import plan_command

test_commands = [
    "open whatsapp",
    "search for pizza places",
    "open whatsapp and search for the family group",
    "type hello and send it",
    "open google and then search for ghost machine and then scroll down",
    "call aashi",
    "scroll down",
]

for cmd in test_commands:
    print(f"\n--- Command: {cmd} ---")
    result = plan_command(cmd, "english")
    for step in result.steps:
        print(f"  {step.intent} -> {step.target}")
