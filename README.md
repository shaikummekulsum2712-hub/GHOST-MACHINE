<div align="center">

# 👻 Ghost Machine

### AI-Powered Android Phone Automation Agent

*Give a natural language command — Ghost Machine sees your screen, thinks, and acts.*

[![Android](https://img.shields.io/badge/Android-API%2030%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![FastAPI](https://img.shields.io/badge/FastAPI-0.100+-009688?logo=fastapi&logoColor=white)](https://fastapi.tiangolo.com)
[![Gemini](https://img.shields.io/badge/Gemini%20AI-Vision-8E75B2?logo=googlegemini&logoColor=white)](https://ai.google.dev)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

**Ghost Machine** is an AI agent that autonomously controls your Android phone using natural language. Tell it what to do in plain English, and it will analyze your screen in real-time, plan the actions, and execute them step-by-step — tapping, swiping, typing — all hands-free.

</div>

---

## ✨ Features

- 🧠 **AI-Powered Understanding** — Powered by Google's Gemini AI (multimodal vision), Ghost Machine actually *sees* and understands your phone's screen
- 👁️ **Vision Loop** — Closed-loop reactive agent that captures a screenshot → thinks → acts → repeats until the goal is done
- 📱 **Real Device Control** — Taps, swipes, and types on your actual phone via Android Accessibility Services
- 💬 **Chat Interface** — Sleek Jetpack Compose chat UI on Android + a premium glassmorphism web companion on PC
- 🖥️ **PC Companion Console** — Send commands from your browser, watch execution live, and see real-time screenshots
- 🔒 **Safety Filters** — Built-in keyword-based guardrails block risky actions (payments, deletions, banking) before execution
- 🔄 **Dual Execution Modes** — Vision Loop (smart, reactive) and Legacy Batch Mode (single-pass planning) for flexibility
- 📡 **Real-Time Sync** — Phone polls the backend for commands; status, screenshots, and progress stream live to the web UI

---

## 🏗️ Architecture

```
┌─────────────────────────────────┐
│         PC Browser              │
│   (Web Companion Console)       │
│   Glassmorphism Chat UI         │
│   Send commands / View status   │
└──────────────┬──────────────────┘
               │ HTTP
               ▼
┌─────────────────────────────────┐
│     Python FastAPI Backend      │
│                                 │
│  • Gemini AI integration        │
│  • Vision & Blind mode prompts  │
│  • Vision Loop state machine    │
│  • Command queue & sync         │
│  • Safety filter engine         │
│  • Screenshot processing (PIL)  │
└──────────────┬──────────────────┘
               │ HTTP (via ADB reverse)
               ▼
┌─────────────────────────────────┐
│     Android App (Kotlin)        │
│                                 │
│  • Jetpack Compose Chat UI      │
│  • Accessibility Service        │
│    (tap, swipe, type, screenshot)│
│  • Vision Loop executor         │
│  • Command polling              │
│  • Status reporting             │
└─────────────────────────────────┘
```

---

## 🚀 Getting Started

### Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| **Python** | 3.9+ | Backend server |
| **Android Studio** | Latest | Build & deploy the Android app |
| **ADB** | Latest | USB debugging & port forwarding |
| **Gemini API Key** | — | AI brain ([get one free](https://aistudio.google.com/apikey)) |

### 1. Clone the Repository

```bash
git clone https://github.com/shaikummekulsum2712-hub/GHOST-MACHINE.git
cd GHOST-MACHINE
```

### 2. Set Up the Backend

```bash
cd backend

# Create a virtual environment (recommended)
python -m venv venv
source venv/bin/activate   # macOS/Linux
venv\Scripts\activate      # Windows

# Install dependencies
pip install -r requirements.txt
```

Create a `.env` file in the `backend/` directory:

```env
GEMINI_API_KEY=your_gemini_api_key_here
```

Start the server:

```bash
uvicorn main:app --host 0.0.0.0 --port 8000
```

You should see:
```
✅ Using batch model: gemini-2.5-flash
✅ Using vision loop model: gemini-2.5-flash
```

### 3. Build & Install the Android App

1. Open the project root in **Android Studio**
2. Connect your Android device via USB with **USB Debugging** enabled
3. Build and run the `app` module on your device

### 4. Set Up ADB Port Forwarding

The Android app communicates with the backend over `localhost`. Forward the port so the phone can reach your PC:

```bash
adb reverse tcp:8000 tcp:8000
```

### 5. Enable the Accessibility Service

On your Android device:

1. Open **Settings → Accessibility**
2. Find **Ghost Machine** in the list
3. Toggle it **ON** and grant permissions

> ⚠️ The accessibility service is required for Ghost Machine to perform taps, swipes, type text, and capture screenshots.

### 6. Start Using Ghost Machine

- **On your phone**: Open the Ghost Machine app and type a command in the chat
- **On your PC**: Navigate to `http://localhost:8000` in your browser for the web companion console

---

## 💡 Usage Examples

| Command | What Ghost Machine Does |
|---------|------------------------|
| `"Open Calculator and tap 5"` | Goes home → opens app drawer → finds Calculator → taps 5 |
| `"Search for biryani on Chrome"` | Opens Chrome → taps search bar → types "biryani" → searches |
| `"Turn on WiFi"` | Navigates to Settings → toggles WiFi on |
| `"Open YouTube and play a trending video"` | Opens YouTube → finds trending → taps a video |

---

## 🔄 How the Vision Loop Works

The Vision Loop is Ghost Machine's most powerful mode — a closed-loop reactive agent:

```
┌──────────────────────────────────────────────────┐
│                  VISION LOOP                     │
│                                                  │
│  1. 📸 Phone captures a screenshot               │
│  2. 🧠 AI analyzes: "What do I see? What next?" │
│  3. 🎯 AI returns ONE action (tap/swipe/type)    │
│  4. 📱 Phone executes the action                 │
│  5. ⏳ Wait for UI to settle                     │
│  6. 🔁 Repeat from step 1                        │
│  7. ✅ AI returns "done" when goal is achieved   │
│                                                  │
│  Max steps: 25 (configurable)                    │
└──────────────────────────────────────────────────┘
```

Unlike batch mode (which plans all steps upfront and hopes coordinates are right), the Vision Loop **sees the result of every action** and adapts its strategy in real-time.

---

## 📁 Project Structure

```
GHOST-MACHINE/
├── app/                              # Android Application (Kotlin)
│   ├── build.gradle.kts              # App-level Gradle config
│   └── src/main/
│       ├── AndroidManifest.xml       # Permissions & service declarations
│       └── java/com/example/ghostmachine/
│           ├── MainActivity.kt       # App entry, command handling, vision loop executor
│           ├── GhostAccessibilityService.kt  # Core: tap, swipe, type, screenshot capture
│           ├── ApiClient.kt          # HTTP client for all backend API calls
│           ├── ChatScreen.kt         # Jetpack Compose chat screen layout
│           ├── ChatBubble.kt         # Message bubble composable
│           ├── ChatMessage.kt        # Message data model & status enum
│           ├── TypingIndicator.kt    # Animated typing dots
│           └── ui/theme/            # Material 3 theming (Color, Theme, Type)
│
├── backend/                          # Python FastAPI Backend
│   ├── main.py                       # Server core: API routes, Gemini integration, state machine
│   ├── frontend.py                   # Embedded HTML/CSS/JS for the web companion console
│   ├── device_profile.py            # Device screen dimensions & coordinate metadata
│   ├── requirements.txt             # Python dependencies
│   ├── test_backend.py              # Integration tests for all API endpoints
│   ├── quick_test.py                # Quick smoke test
│   ├── action.json                  # Last AI-generated action plan (debug artifact)
│   └── agent/                       # Agent modules
│       ├── action_schema.py         # Pydantic models for actions & requests
│       ├── safety_filter.py         # Keyword-based risky action blocker
│       ├── agent_loop.py            # Agent loop abstractions
│       ├── llm_client.py            # LLM client wrapper
│       ├── prompt_builder.py        # System prompt construction
│       └── mock_agent.py            # Mock agent for testing
│
├── build.gradle.kts                  # Root Gradle config
├── settings.gradle.kts               # Gradle settings (project name, modules)
├── gradle.properties                 # JVM args, Kotlin code style
└── .gitignore
```

---

## 🔌 API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | `GET` | Web companion console (HTML UI) |
| `/next-action` | `POST` | Send a command; returns AI-planned action steps |
| `/upload-screenshot` | `POST` | Upload a device screenshot; optionally re-plan a command with vision |
| `/poll-command` | `GET` | Phone polls for queued commands from the web UI |
| `/report-status` | `POST` | Phone reports execution progress back to the backend |
| `/execution-status` | `GET` | Get device connection state, execution status, and message history |
| `/reset-sync` | `POST` | Clear all pending commands, messages, and screenshots |
| `/reset` | `POST` | Reset the AI chat session and vision loop state |
| `/vision-loop/start` | `POST` | Start a new vision loop with a goal |
| `/vision-loop/screenshot` | `POST` | Send a screenshot for AI analysis; returns the next single action |
| `/vision-loop/action-complete` | `POST` | Report that the phone executed an action |
| `/vision-loop/status` | `GET` | Get the full vision loop state |
| `/vision-loop/abort` | `POST` | Emergency stop the running vision loop |
| `/vision-loop/screenshot-preview` | `GET` | Get the latest screenshot as base64 for web UI display |

---

## 🛡️ Safety System

Ghost Machine includes a safety filter that intercepts risky actions before they reach your phone. Commands or actions containing sensitive keywords are automatically blocked:

> `pay` · `payment` · `send` · `delete` · `confirm` · `submit` · `order` · `bank` · `upi` · `transfer` · `purchase`

When a risky keyword is detected, the action is replaced with an `ask_user` response, requiring explicit user confirmation before proceeding.

---

## 🧪 Running Tests

```bash
cd backend
python -m pytest test_backend.py -v
```

The test suite spins up the FastAPI server, validates all endpoints (home page, command processing, vision loop lifecycle), and tears down cleanly.

---

## ⚙️ Configuration

### Device Profile

Edit [`backend/device_profile.py`](backend/device_profile.py) to match your device:

```python
DEVICE_PROFILE = {
    "model": "Your Device Model",
    "screen_width": 1080,       # Your screen width in pixels
    "screen_height": 2400,      # Your screen height in pixels
    "density_dpi": 420,         # Your screen density
    "android_version": "14",    # Your Android version
}
```

Get your device info via ADB:
```bash
adb shell wm size            # Screen resolution
adb shell wm density         # Screen density
adb shell getprop ro.product.model  # Device model
```

### Gemini Model Selection

The backend automatically tries multiple Gemini models in order of preference:

1. `gemini-2.5-flash`
2. `gemini-2.0-flash-lite`
3. `gemini-2.0-flash`
4. `gemini-1.5-flash`

All models work with the free tier API key.

---

## 🤝 Contributing

Contributions are welcome! Here's how to get started:

1. **Fork** the repository
2. **Create** a feature branch: `git checkout -b feature/my-feature`
3. **Commit** your changes: `git commit -m "Add my feature"`
4. **Push** to the branch: `git push origin feature/my-feature`
5. **Open** a Pull Request

---

## 📄 License

This project is open source and available under the [MIT License](LICENSE).

---

<div align="center">

**Built with 🤖 AI + ❤️ by the Ghost Machine team**

</div>
