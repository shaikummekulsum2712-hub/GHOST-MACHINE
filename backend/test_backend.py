import os
import json
import time
import subprocess
import urllib.request
import urllib.error
import unittest

class TestGhostBackend(unittest.TestCase):
    server_process = None
    BASE_URL = "http://127.0.0.1:8000"

    @classmethod
    def setUpClass(cls):
        # Start the FastAPI backend with uvicorn in a subprocess
        cls.server_process = subprocess.Popen(
            ["python", "-m", "uvicorn", "main:app", "--host", "127.0.0.1", "--port", "8000"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            cwd=os.path.dirname(__file__)
        )
        # Give the server a moment to start up and bind to the port
        time.sleep(2.0)

    @classmethod
    def tearDownClass(cls):
        # Terminate the server process
        if cls.server_process:
            cls.server_process.terminate()
            cls.server_process.wait()

    def test_home_endpoint(self):
        try:
            req = urllib.request.Request(f"{self.BASE_URL}/")
            with urllib.request.urlopen(req) as response:
                self.assertEqual(response.status, 200)
                html = response.read().decode()
                self.assertIn("<!DOCTYPE html>", html)
                self.assertIn("Ghost Machine", html)
        except urllib.error.URLError as e:
            self.fail(f"Failed to connect to server: {e}")

    def test_next_action_endpoint(self):
        # Remove action.json if it exists prior to test to ensure new one is created
        action_file = os.path.join(os.path.dirname(__file__), "action.json")
        if os.path.exists(action_file):
            try:
                os.remove(action_file)
            except Exception:
                pass

        payload = json.dumps({"command": "Open Calculator and Tap 5"}).encode("utf-8")
        req = urllib.request.Request(
            f"{self.BASE_URL}/next-action",
            data=payload,
            headers={"Content-Type": "application/json"}
        )

        try:
            with urllib.request.urlopen(req) as response:
                self.assertEqual(response.status, 200)
                data = json.loads(response.read().decode())
                
                # Check response fields
                self.assertIn("summary", data)
                self.assertIn("steps", data)
                self.assertIsInstance(data.get("steps"), list)
                
                # Check that action.json is physically generated
                self.assertTrue(os.path.exists(action_file), "action.json was not created")
                
                with open(action_file, "r") as f:
                    file_content = json.load(f)
                
                self.assertIn("summary", file_content)
                self.assertIn("steps", file_content)
                self.assertIsInstance(file_content.get("steps"), list)
                
        except urllib.error.URLError as e:
            self.fail(f"Failed to connect to server: {e}")

if __name__ == "__main__":
    unittest.main()
