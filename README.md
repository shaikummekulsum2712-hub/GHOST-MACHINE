# 👻 Ghost Machine

> An AI-powered Android accessibility agent capable of understanding natural language voice commands, reasoning about the current screen, and performing actions autonomously using Android Accessibility APIs and Vision-Language Models.

---

# 📖 Overview

Ghost Machine is an experimental Android AI Agent that attempts to interact with Android devices the same way a human would.

Instead of relying only on predefined automation scripts, Ghost Machine observes the screen, understands user intent, reasons about UI elements, and decides how to complete a task.

The long-term vision is to build an autonomous mobile agent that can operate any Android application without requiring app-specific integrations.

Examples:

- "Open WhatsApp and message Mom."
- "Search for Machine Learning videos on YouTube."
- "Open Gmail."
- "Scroll down."
- "Type Hello."

---

# ❓ Why Ghost Machine?

Current Android assistants are usually limited because they:

- depend on predefined intents
- only work with supported apps
- require hardcoded automation
- fail whenever the UI changes

Ghost Machine instead treats Android as a visual environment.

It attempts to:

- understand the screen
- locate UI elements
- reason about uncertainty
- ask for clarification when needed
- execute actions dynamically

rather than depending on hardcoded workflows.

---

# ✨ Features

## 🎤 Voice Commands

- Android SpeechRecognizer
- Multilingual command support
- English
- Hinglish
- Telugu (Romanized)

Examples:

> Open Gmail

> Search for ChatGPT

> Scroll Down

> Type Hello World

> Gmail kholo

> Search cheyyi Python

---

## 🧠 Intent Parsing

Commands are normalized before execution.

Examples

```
Search karo Python
```

↓

```
search for python
```

or

```
Search cheyyi AI
```

↓

```
search for AI
```

The parser extracts

- intent
- target
- reply language

which are later used by both Android logic and the backend planner.

---

## 🔊 Multilingual Responses

Ghost Machine responds back to the user using Android Text-To-Speech.

Currently supported:

- English
- Hinglish
- Telugu

Example

User:

> Gmail kholo

Ghost replies

> Samajh gaya... Gmail khol raha hoon.

---

## 📱 Android-First Decision Engine

Ghost Machine **always tries Android APIs first**.

This avoids unnecessary AI calls.

Examples:

- Home
- Back
- Scroll
- Tap
- Type
- Search

If Android can confidently execute the task,
no screenshot is sent to the backend.

This makes execution:

- faster
- cheaper
- more reliable

---

## 👀 Accessibility Tree Parsing

The Accessibility Service extracts:

- text
- content description
- bounds
- clickable state
- editable state
- enabled state

These elements are converted into structured JSON.

---

## 📸 Vision-Language Reasoning

When Android cannot confidently identify the correct UI element,

Ghost Machine captures the current screen and sends:

- screenshot
- parsed command
- accessibility tree
- previous action
- uncertainty reason

to the backend.

The Vision-Language Model reasons about the UI and returns the next action.

---

## 🔁 Iterative Planning Loop

Ghost Machine does not execute only once.

Instead it follows an execution loop.

```
Voice Command

↓

Intent Parsing

↓

Accessibility Tree

↓

Android Decision Engine

↓

Confident?

YES
↓

Execute

↓

Check if completed

↓

Done

------------------

NO

↓

Screenshot

↓

Vision Model

↓

Receive Plan

↓

Execute

↓

Repeat
```

This allows multi-step reasoning.

---

# 🧩 Project Architecture

```
Android App

│

├── Accessibility Service

├── Voice Recognition

├── Intent Parser

├── Android Decision Engine

├── Screenshot Capture

├── Backend API Client

└── Text To Speech

            │

            ▼

FastAPI Backend

│

├── Request Validation

├── Planner

├── Vision-Language Model

├── Action Selection

└── Response
```

---

# ⚙ Current Backend

Current backend is built using

- FastAPI
- Python
- Ollama
- Local Vision Language Models

Initially the project used external APIs.

It has since been migrated to local models running through Ollama.

---

# 🧠 Current Reasoning Flow

The backend receives

- screenshot
- UI elements
- parsed intent
- target
- previous action
- uncertainty

The planner decides:

- tap
- scroll
- type
- back
- home
- ask user

and returns the selected action.

---

# 🎯 Current Capabilities

✅ Voice Commands

✅ Android Accessibility

✅ Screen Parsing

✅ Local Vision Model

✅ Android First Execution

✅ Backend Planning

✅ Search

✅ Type

✅ Tap

✅ Scroll

✅ Home

✅ Back

✅ Multi-language Responses

---

# 🚧 Current Limitations

Ghost Machine is still under active development.

Current limitations include:

- no long-term memory
- limited task planning depth
- no persistent conversation history
- no automatic recovery from every failure
- simple language detection
- screenshot-based reasoning only
- no semantic UI embeddings yet

---

# 🔮 Planned Improvements

## Multi-Step Planning Loop

Instead of planning one action at a time,

Ghost Machine will generate an execution plan and continuously evaluate whether the task has been completed.

---

## UI Element Memory

Store previously detected UI elements.

Instead of asking the Vision Model repeatedly,

Ghost Machine will:

- reuse known elements
- track UI changes
- update only changed regions

This greatly reduces inference time.

---

## Visual Grid Reasoning

Overlay coordinate grids onto screenshots.

The Vision Model will return

```
Tap Grid B4
```

instead of pixel coordinates.

This improves localization accuracy.

---

## Dynamic Confidence System

Current Android decisions use fixed confidence values.

Future versions will use learned confidence scores based on:

- UI similarity
- historical success
- previous actions
- task context

---

## Continuous Screen Tracking

Rather than sending a complete screenshot every step,

future versions will send only changed screen regions.

This reduces latency significantly.

---

## Local Speech Models

Replace Android SpeechRecognizer with:

- Faster Whisper

or other offline multilingual speech models.

Benefits:

- offline support
- better multilingual accuracy
- privacy

---

## Advanced Language Understanding

Future versions aim to support unrestricted multilingual speech without hardcoded dictionaries.

---

## Agent Memory

Maintain context across actions.

Example:

User

> Open WhatsApp

↓

Later

> Send him Hello

The agent should understand who "him" refers to.

---

## LangGraph Integration

Future versions may integrate LangGraph for:

- planning
- branching
- retries
- memory
- recovery

making Ghost Machine a true agent rather than a request-response system.

---

# 🛠 Tech Stack

## Android

- Kotlin
- Accessibility Service
- SpeechRecognizer
- TextToSpeech
- OkHttp

## Backend

- Python
- FastAPI
- Uvicorn

## AI

- Ollama
- Qwen Vision Models
- Vision Language Reasoning

---

# 🚀 Setup

## Clone Repository

```bash
git clone https://github.com/<your-username>/GHOST-MACHINE.git
```

---

## ⚠ IMPORTANT

The **main** branch is **not** the latest development version.

The active development branch is:

```text
ghostmachine
```

Switch to it immediately after cloning:

```bash
git checkout ghostmachine
```

---

## Android

Open project using

Android Studio

Grant

- Accessibility Permission
- Microphone Permission

---

## Backend

Navigate to backend

```bash
cd backend
```

Install

```bash
pip install -r requirements.txt
```

Run

```bash
python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

---

## Ollama

Install Ollama

Download your preferred Vision Language Model.

Example

```bash
ollama pull qwen3-vl:2b
```

Verify

```bash
ollama list
```

Run

```bash
ollama serve
```

---

## Configure Android

Update

```
ApiClient.kt
```

with your backend IP

Example

```
http://192.xxx.x.xx:8000
```

Do **not** use

```
127.0.0.1
```

unless using adb reverse.

---

# GhostMachine — Setup & Run Workflow

This guide covers the full checklist for getting GhostMachine running from scratch on a new machine, using **VS Code + command line** (no Android Studio required). Follow it top to bottom the first time; use the [Quick Reference](#quick-reference-every-time-after-first-setup) section for every run after that.

> **Branch note:** Always work on the `ghostmachine` branch, not `main`.
> ```bash
> git checkout ghostmachine
> ```

---

## Table of Contents

1. [One-Time Prerequisites](#1-one-time-prerequisites)
2. [Machine Layout — Decide This First](#2-machine-layout--decide-this-first)
3. [Pull the AI Models](#3-pull-the-ai-models)
4. [Configure the Backend (`.env`)](#4-configure-the-backend-env)
5. [Configure the Android App (`ApiClient.kt`)](#5-configure-the-android-app-apiclientkt)
6. [Start the Servers](#6-start-the-servers)
7. [Build and Install the App (No Android Studio)](#7-build-and-install-the-app-no-android-studio)
8. [Enable Permissions on the Phone](#8-enable-permissions-on-the-phone)
9. [Verify Everything Is Connected](#9-verify-everything-is-connected)
10. [Watching Logs While Testing](#10-watching-logs-while-testing)
11. [Quick Reference — Every Time After First Setup](#11-quick-reference--every-time-after-first-setup)
12. [Troubleshooting Checklist](#12-troubleshooting-checklist)

---

## 1. One-Time Prerequisites

Install these once on whichever machine will run the backend:

- **Python 3.11+** and `pip`
- **Ollama** — [ollama.com](https://ollama.com)
- **Android SDK platform-tools** (for `adb`) — comes bundled with Android Studio, or install standalone via [developer.android.com/tools/releases/platform-tools](https://developer.android.com/tools/releases/platform-tools)
- **Java JDK** (required by Gradle to build the app) — JDK 17 is a safe default
- A physical Android phone, **API 30+ (Android 11 or newer)**, with **Developer Options** and **USB debugging** enabled (Settings → About Phone → tap "Build Number" 7 times → Settings → Developer Options → enable USB debugging)

Confirm `adb` is reachable from a terminal:
```bash
adb version
```
If this fails, either add `platform-tools` to your system PATH, or use the full path to `adb.exe` in every command below (e.g. `C:\Users\<you>\AppData\Local\Android\Sdk\platform-tools\adb.exe`).

---

## 2. Machine Layout — Decide This First

GhostMachine has three pieces that all need to reach each other over the network:

| Piece | What it is |
|---|---|
| **Phone** | Runs the Android app, needs to reach the backend |
| **Backend** | FastAPI/uvicorn server — needs to reach Ollama |
| **Ollama** | Serves the AI models — can be local to the backend, or on a separate machine |

**Simplest setup (recommended for a demo):** run the backend **and** Ollama on the same machine. The phone only needs to know that one machine's IP address.

If backend and Ollama are on different machines, you'll need two IPs configured in two different places — see the notes in each config step below.

---

## 3. Pull the AI Models

Two different models are used for two different jobs — don't reuse one for both:

```bash
# Vision model - reads screenshots, decides on-screen actions
ollama pull qwen3-vl:2b

# Planner model - splits compound voice commands into steps (text-only, no vision)
ollama pull qwen3:14b
```
> If `qwen3:14b` is too large/slow for your hardware, a smaller text model like `qwen2.5:1.5b` also works — just update `OLLAMA_PLANNER_MODEL` in step 4 to match.

Confirm both are present:
```bash
ollama list
```

---

## 4. Configure the Backend (`.env`)

Create or edit `backend/.env`:

```env
OLLAMA_BASE_URL=http://127.0.0.1:11434
OLLAMA_MODEL=qwen3-vl:2b
OLLAMA_PLANNER_MODEL=qwen3:14b
PLANNER_PROVIDER=ollama
```

- Use `127.0.0.1` for `OLLAMA_BASE_URL` if Ollama runs on the **same machine** as the backend.
- If Ollama runs on a **different machine**, replace `127.0.0.1` with that machine's LAN IP, and make sure Ollama on that machine is started with `OLLAMA_HOST=0.0.0.0:11434` so it accepts external connections (see step 6).

Install backend dependencies:
```bash
cd backend
pip install -r requirements.txt
```

---

## 5. Configure the Android App (`ApiClient.kt`)

Find the backend machine's LAN IP:
```bash
ipconfig        # Windows - look under "Wireless LAN adapter Wi-Fi"
# or
ifconfig        # macOS/Linux
```

Open `app/src/main/java/com/example/ghostmachine/ApiClient.kt` and set:
```kotlin
private const val BASE_URL = "http://<backend-machine-IP>:8000"
```

> **This IP changes** whenever the backend machine reconnects to WiFi (DHCP reassignment). If the phone suddenly can't reach the backend, re-check `ipconfig` first — this is the single most common cause of connection failures.

---

## 6. Start the Servers

Open **3 terminal tabs** in VS Code (Terminal → New Terminal, then use the `+` icon for more tabs). All three must stay running simultaneously.

**Terminal 1 — Ollama**
```bash
ollama serve
```
If Ollama and the backend are on different machines, set this env var first so Ollama accepts connections from outside itself:
```bash
# Windows PowerShell
$env:OLLAMA_HOST="0.0.0.0:11434"
ollama serve
```

**Terminal 2 — Backend**
```bash
cd backend
python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```
Wait for:
```
Uvicorn running on http://0.0.0.0:8000
```
> Always bind to `0.0.0.0`, never to the machine's specific IP directly — binding to a specific IP can fail with `WinError 10049` depending on network adapter state.

**Terminal 3 — free for adb, logs, and testing commands** (used throughout the rest of this guide).

---

## 7. Build and Install the App (No Android Studio)

From the **project root** (not `backend`):

```bash
# Windows
.\gradlew.bat assembleDebug

# macOS/Linux
./gradlew assembleDebug
```

Wait for `BUILD SUCCESSFUL`. The APK is now at:
```
app/build/outputs/apk/debug/app-debug.apk
```

**Connect the phone via USB**, allow the "Allow USB debugging" prompt on the phone if it appears, then confirm it's detected:
```bash
adb devices
```
It should list your device as authorized (not "unauthorized" or "offline"). If it shows nothing, check the USB cable/port and that USB debugging is enabled.

**Install:**
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
The `-r` flag reinstalls cleanly over any previous build — use this every time.

**No USB available?** Use wireless ADB instead (needs one-time USB pairing first, Android 11+):
```bash
adb pair <phone-ip>:<pairing-port>   # code shown in phone: Settings > Developer Options > Wireless debugging > Pair device
adb connect <phone-ip>:<port>
```

---

## 8. Enable Permissions on the Phone

1. Open the app once — grant **microphone** permission when prompted.
2. Go to **Settings → Accessibility → GhostMachine → turn ON**.
3. Whenever `res/xml/accessibility_service_config.xml` changes between builds, **toggle the accessibility service off and back on** — Android caches its declared capabilities at bind time and won't pick up XML changes otherwise.

---

## 9. Verify Everything Is Connected

Before testing any voice command, confirm the chain works end-to-end:

1. **Phone and backend machine are on the same WiFi network.**
2. From the **phone's own browser**, visit:
   ```
   http://<backend-machine-IP>:8000/docs
   ```
   This should load FastAPI's interactive docs page. If it doesn't load, this is a network/firewall issue — not an app bug. Check:
   - Windows Firewall on the backend machine allows inbound port `8000`.
   - The backend machine's IP hasn't changed since you set `BASE_URL` (re-check `ipconfig`).
3. Confirm both models are actually loaded and ready:
   ```bash
   ollama ps
   ```
4. **Do one throwaway voice command as a warm-up** before presenting — cold model loads can take 30–100+ seconds, and you don't want that delay happening live in front of an audience.

---

## 10. Watching Logs While Testing

**Android-side logs** (in Terminal 3):
```bash
adb logcat -s GhostService:*
```

**Backend logs** are already visible directly in Terminal 2's output — watch for lines like `Command received:`, `Ollama status code:`, and `RAW PLANNER OUTPUT:`.

**Dashboard** (visual view of the step timeline + screenshots): open in any browser, from any device on the same network:
```
http://<backend-machine-IP>:8000/dashboard.html
```

When debugging a specific failure, capture **both** the Android logcat output and the backend terminal output for the same command — the two together are what make root-causing an issue possible instead of guessing.

---

## 11. Quick Reference — Every Time After First Setup

Once everything above has been done once, here's the repeatable cycle for making a change and testing it again:

```bash
# Terminal 1 (leave running)
ollama serve

# Terminal 2 (leave running)
cd backend
python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload

# Terminal 3 - after every Android code change:
.\gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s GhostService:*
```

If only Python files changed, uvicorn's `--reload` picks them up automatically — no restart needed. If Kotlin files changed, you must rebuild + reinstall (the two commands above) every time.

---

## 12. Troubleshooting Checklist

Work through these in order when something isn't working — most issues tonight came down to one of these:

- [ ] Is the backend machine actually connected to WiFi? (`ipconfig` — look for "Media disconnected")
- [ ] Has the backend machine's IP changed since `ApiClient.kt` was last updated?
- [ ] Is `ollama serve` actually running? (`ollama ps`)
- [ ] Is uvicorn actually running, with no startup errors/tracebacks in its terminal?
- [ ] Does `http://<backend-IP>:8000/docs` load from the **phone's** browser?
- [ ] Is the accessibility service toggled ON, and re-toggled after any config XML change?
- [ ] Is microphone permission granted?
- [ ] Does `adb devices` show the phone as authorized?
- [ ] Did you rebuild + reinstall after the last Kotlin change? (Python changes hot-reload; Kotlin changes do not.)
- [ ] Consider setting a **DHCP reservation** for the backend machine's MAC address in your router settings — this permanently stops the IP-change issue from recurring.

# 📌 Project Status

This project is actively under development.

The current implementation demonstrates the core architecture for an autonomous Android AI agent.

The long-term goal is to create a fully autonomous Android operating agent capable of understanding natural language, reasoning over arbitrary user interfaces, planning multi-step tasks, and interacting with mobile devices similarly to a human.

---

# 📄 License

This project is intended for research and educational purposes.
