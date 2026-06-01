import urllib.request
import json

req = urllib.request.Request(
    "http://127.0.0.1:8000/next-action",
    data=json.dumps({"command": "Open calculator and tap 5"}).encode(),
    headers={"Content-Type": "application/json"}
)
resp = urllib.request.urlopen(req)
data = json.loads(resp.read())

print("Summary:", data.get("summary"))
print(f"Steps: {len(data.get('steps', []))}")
for i, s in enumerate(data.get("steps", [])):
    print(f"  {i+1}. [{s['action']}] {s.get('reason', '')}")
