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

# 📌 Project Status

This project is actively under development.

The current implementation demonstrates the core architecture for an autonomous Android AI agent.

The long-term goal is to create a fully autonomous Android operating agent capable of understanding natural language, reasoning over arbitrary user interfaces, planning multi-step tasks, and interacting with mobile devices similarly to a human.

---

# 📄 License

This project is intended for research and educational purposes.
