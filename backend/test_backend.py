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
            with urllib.request.urlopen(req, timeout=10) as response:
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
            with urllib.request.urlopen(req, timeout=10) as response:
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

    def test_vision_loop_lifecycle(self):
        # 1. Start Vision Loop
        start_payload = json.dumps({"goal": "Test Vision Loop Integration", "max_steps": 10}).encode("utf-8")
        start_req = urllib.request.Request(
            f"{self.BASE_URL}/vision-loop/start",
            data=start_payload,
            headers={"Content-Type": "application/json"}
        )

        try:
            with urllib.request.urlopen(start_req, timeout=10) as response:
                self.assertEqual(response.status, 200)
                data = json.loads(response.read().decode())
                self.assertIn("loop_id", data)
                self.assertEqual(data["status"], "WAITING_SCREENSHOT")
                loop_id = data["loop_id"]
        except urllib.error.URLError as e:
            self.fail(f"Failed to start vision loop: {e}")

        # 2. Check status is active
        status_req = urllib.request.Request(f"{self.BASE_URL}/vision-loop/status")
        try:
            with urllib.request.urlopen(status_req, timeout=10) as response:
                self.assertEqual(response.status, 200)
                status_data = json.loads(response.read().decode())
                self.assertTrue(status_data["active"])
                self.assertEqual(status_data["loop_id"], loop_id)
                self.assertEqual(status_data["status"], "WAITING_SCREENSHOT")
                self.assertEqual(status_data["goal"], "Test Vision Loop Integration")
        except urllib.error.URLError as e:
            self.fail(f"Failed to get vision loop status: {e}")

        # 3. Report action complete
        action_complete_payload = json.dumps({
            "loop_id": loop_id,
            "step_index": 1,
            "success": True
        }).encode("utf-8")
        action_req = urllib.request.Request(
            f"{self.BASE_URL}/vision-loop/action-complete",
            data=action_complete_payload,
            headers={"Content-Type": "application/json"}
        )
        try:
            with urllib.request.urlopen(action_req, timeout=10) as response:
                self.assertEqual(response.status, 200)
                action_data = json.loads(response.read().decode())
                self.assertEqual(action_data["status"], "WAITING_SCREENSHOT")
        except urllib.error.URLError as e:
            self.fail(f"Failed to report action complete: {e}")

        # 4. Abort loop
        abort_req = urllib.request.Request(
            f"{self.BASE_URL}/vision-loop/abort",
            data=b"{}",
            headers={"Content-Type": "application/json"}
        )
        try:
            with urllib.request.urlopen(abort_req, timeout=10) as response:
                self.assertEqual(response.status, 200)
                abort_data = json.loads(response.read().decode())
                self.assertEqual(abort_data["status"], "aborted")
        except urllib.error.URLError as e:
            self.fail(f"Failed to abort vision loop: {e}")

        # 5. Check status is idle
        try:
            with urllib.request.urlopen(status_req, timeout=10) as response:
                self.assertEqual(response.status, 200)
                status_data = json.loads(response.read().decode())
                self.assertFalse(status_data["active"])
                self.assertEqual(status_data["status"], "IDLE")
        except urllib.error.URLError as e:
            self.fail(f"Failed to check post-abort status: {e}")

if __name__ == "__main__":
    unittest.main()
