from agent.planner import plan_command

print("GhostMachine planner test - type a command, or 'quit' to exit.\n")

while True:
    cmd = input("Command: ").strip()
    if cmd.lower() in ("quit", "exit", "q"):
        break
    if not cmd:
        continue

    result = plan_command(cmd, "english")
    print(f"\n  {len(result.steps)} step(s):")
    for i, step in enumerate(result.steps, 1):
        print(f"  {i}. {step.intent} -> {step.target}")
    print()
