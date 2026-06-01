# -*- coding: utf-8 -*-
"""
Premium frontend UI for Ghost Machine companion console.
"""

HTML_CONTENT = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Ghost Machine — PC Companion Chat Console</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=Outfit:wght@400;600;800&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg-base: #0a0b0e;
            --bg-surface: #12131a;
            --bg-surface-glass: rgba(25, 27, 38, 0.6);
            --border-glass: rgba(255, 255, 255, 0.08);
            
            --color-primary: #4a6cf7;
            --color-primary-glow: rgba(74, 108, 247, 0.35);
            --color-accent: #6366f1;
            --color-accent-glow: rgba(99, 102, 241, 0.35);
            
            --color-text-main: #f3f4f6;
            --color-text-muted: #9ca3af;
            
            --status-success: #10b981;
            --status-error: #ef4444;
            --status-warning: #f59e0b;
            --status-info: #3b82f6;
        }

        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
        }

        body {
            background-color: var(--bg-base);
            color: var(--color-text-main);
            font-family: 'Inter', sans-serif;
            height: 100vh;
            overflow: hidden;
            display: flex;
            flex-direction: column;
        }

        /* ── Header ── */
        header {
            height: 70px;
            background: rgba(18, 19, 26, 0.8);
            backdrop-filter: blur(12px);
            border-bottom: 1px solid var(--border-glass);
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 0 30px;
            z-index: 100;
        }

        .logo-container {
            display: flex;
            align-items: center;
            gap: 12px;
        }

        .logo-icon {
            font-size: 28px;
            animation: float 3s ease-in-out infinite;
        }

        .logo-text {
            font-family: 'Outfit', sans-serif;
            font-size: 22px;
            font-weight: 800;
            background: linear-gradient(135deg, #a5b4fc, #6366f1);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            letter-spacing: 0.5px;
        }

        .device-status-badge {
            display: flex;
            align-items: center;
            gap: 8px;
            padding: 8px 16px;
            border-radius: 20px;
            background: rgba(255, 255, 255, 0.03);
            border: 1px solid var(--border-glass);
            font-size: 13px;
            font-weight: 500;
        }

        .status-dot {
            width: 8px;
            height: 8px;
            border-radius: 50%;
            background-color: var(--color-text-muted);
            box-shadow: 0 0 8px transparent;
        }

        .status-dot.connected {
            background-color: var(--status-success);
            box-shadow: 0 0 10px var(--status-success);
            animation: pulse-green 2s infinite;
        }

        .status-dot.disconnected {
            background-color: var(--status-error);
            box-shadow: 0 0 10px var(--status-error);
        }

        /* ── Main Layout ── */
        .main-container {
            flex: 1;
            display: flex;
            height: calc(100vh - 70px);
            overflow: hidden;
        }

        /* ── Chat Console ── */
        .chat-section {
            flex: 1;
            display: flex;
            flex-direction: column;
            border-right: 1px solid var(--border-glass);
            background: radial-gradient(circle at top right, rgba(99, 102, 241, 0.05), transparent 60%);
            position: relative;
        }

        .messages-container {
            flex: 1;
            overflow-y: auto;
            padding: 30px;
            display: flex;
            flex-direction: column;
            gap: 24px;
            scroll-behavior: smooth;
        }

        .messages-container::-webkit-scrollbar {
            width: 6px;
        }
        .messages-container::-webkit-scrollbar-track {
            background: transparent;
        }
        .messages-container::-webkit-scrollbar-thumb {
            background: rgba(255, 255, 255, 0.1);
            border-radius: 3px;
        }

        /* Empty state */
        .empty-state {
            margin: auto;
            text-align: center;
            max-width: 420px;
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 16px;
            color: var(--color-text-muted);
        }

        .empty-ghost {
            font-size: 64px;
            margin-bottom: 10px;
            opacity: 0.8;
            animation: hover-ghost 4s ease-in-out infinite;
        }

        .empty-state h3 {
            font-family: 'Outfit', sans-serif;
            font-size: 20px;
            font-weight: 600;
            color: var(--color-text-main);
        }

        /* Chat bubbles */
        .message-row {
            display: flex;
            width: 100%;
        }

        .message-row.user {
            justify-content: flex-end;
        }

        .message-row.ai {
            justify-content: flex-start;
        }

        .chat-bubble {
            max-width: 75%;
            padding: 16px 20px;
            border-radius: 18px;
            position: relative;
            font-size: 15px;
            line-height: 1.5;
            box-shadow: 0 4px 20px rgba(0,0,0,0.15);
        }

        .message-row.user .chat-bubble {
            background: linear-gradient(135deg, var(--color-primary), var(--color-accent));
            color: white;
            border-bottom-right-radius: 4px;
            box-shadow: 0 4px 15px var(--color-primary-glow);
        }

        .message-row.ai .chat-bubble {
            background: var(--bg-surface-glass);
            border: 1px solid var(--border-glass);
            backdrop-filter: blur(10px);
            border-bottom-left-radius: 4px;
            padding-left: 24px;
        }

        .ai-avatar {
            position: absolute;
            left: -40px;
            top: 0;
            width: 32px;
            height: 32px;
            border-radius: 50%;
            background: rgba(255, 255, 255, 0.05);
            border: 1px solid var(--border-glass);
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 16px;
        }

        .msg-timestamp {
            font-size: 11px;
            color: var(--color-text-muted);
            margin-top: 6px;
            text-align: right;
        }

        /* Status chip */
        .status-chip {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            padding: 4px 10px;
            border-radius: 12px;
            font-size: 11px;
            font-weight: 600;
            text-transform: uppercase;
            letter-spacing: 0.5px;
            margin-top: 10px;
            border: 1px solid transparent;
        }

        .status-chip.idle {
            background: rgba(255, 255, 255, 0.05);
            color: var(--color-text-muted);
        }

        .status-chip.queued {
            background: rgba(59, 130, 246, 0.15);
            color: var(--status-info);
            border-color: rgba(59, 130, 246, 0.2);
            animation: flash-pulse 1.5s infinite;
        }

        .status-chip.executing {
            background: rgba(245, 158, 11, 0.15);
            color: var(--status-warning);
            border-color: rgba(245, 158, 11, 0.2);
            animation: flash-pulse 1.5s infinite;
        }

        .status-chip.success {
            background: rgba(16, 185, 129, 0.15);
            color: var(--status-success);
            border-color: rgba(16, 185, 129, 0.2);
        }

        .status-chip.failed {
            background: rgba(239, 68, 68, 0.15);
            color: var(--status-error);
            border-color: rgba(239, 68, 68, 0.2);
        }

        /* Steps preview in chat */
        .chat-steps-list {
            margin-top: 14px;
            border-top: 1px solid var(--border-glass);
            padding-top: 12px;
            display: flex;
            flex-direction: column;
            gap: 8px;
        }

        .chat-step-item {
            display: flex;
            align-items: flex-start;
            gap: 10px;
            padding: 8px 12px;
            border-radius: 8px;
            background: rgba(255,255,255,0.02);
            border: 1px solid transparent;
            font-size: 13px;
            cursor: pointer;
            transition: all 0.2s ease;
        }

        .chat-step-item:hover {
            background: rgba(255, 255, 255, 0.05);
            border-color: rgba(255, 255, 255, 0.05);
        }

        .chat-step-item.active {
            background: rgba(99, 102, 241, 0.08);
            border-color: rgba(99, 102, 241, 0.2);
        }

        .chat-step-item.completed {
            opacity: 0.6;
        }

        .step-num-badge {
            width: 20px;
            height: 20px;
            border-radius: 50%;
            background: rgba(255,255,255,0.06);
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 11px;
            font-weight: 600;
            flex-shrink: 0;
            margin-top: 1px;
        }

        .chat-step-item.active .step-num-badge {
            background: var(--color-accent);
            color: white;
            box-shadow: 0 0 6px var(--color-accent);
        }

        .step-details {
            display: flex;
            flex-direction: column;
            gap: 2px;
        }

        .step-action-tag {
            font-family: 'JetBrains Mono', monospace;
            font-size: 11px;
            font-weight: 600;
            color: var(--color-primary);
            text-transform: uppercase;
        }

        .step-reason {
            color: var(--color-text-main);
        }

        /* ── Input bar ── */
        .input-section {
            padding: 24px 30px;
            background: rgba(18, 19, 26, 0.6);
            border-top: 1px solid var(--border-glass);
            backdrop-filter: blur(10px);
        }

        .input-wrapper {
            max-width: 900px;
            margin: 0 auto;
            position: relative;
            display: flex;
            align-items: center;
        }

        .prompt-input {
            width: 100%;
            height: 56px;
            background: #171821;
            border: 1px solid var(--border-glass);
            border-radius: 28px;
            padding: 0 80px 0 28px;
            font-family: inherit;
            font-size: 15px;
            color: var(--color-text-main);
            outline: none;
            transition: all 0.3s ease;
            box-shadow: inset 0 2px 4px rgba(0,0,0,0.1);
        }

        .prompt-input:focus {
            border-color: var(--color-accent);
            box-shadow: 0 0 0 3px var(--color-accent-glow), inset 0 2px 4px rgba(0,0,0,0.1);
        }

        .send-btn {
            position: absolute;
            right: 8px;
            width: 40px;
            height: 40px;
            border-radius: 50%;
            background: linear-gradient(135deg, var(--color-primary), var(--color-accent));
            border: none;
            cursor: pointer;
            display: flex;
            align-items: center;
            justify-content: center;
            color: white;
            box-shadow: 0 4px 10px var(--color-primary-glow);
            transition: all 0.2s ease;
        }

        .send-btn:hover {
            transform: scale(1.05);
            box-shadow: 0 4px 15px var(--color-accent-glow);
        }

        .send-btn:active {
            transform: scale(0.95);
        }

        .send-btn svg {
            width: 18px;
            height: 18px;
            fill: none;
            stroke: currentColor;
            stroke-width: 2;
            stroke-linecap: round;
            stroke-linejoin: round;
        }

        /* ── Typing indicator ── */
        .typing-bubble {
            display: flex;
            align-items: center;
            gap: 5px;
            padding: 12px 18px;
        }

        .typing-dot {
            width: 8px;
            height: 8px;
            background-color: var(--color-text-muted);
            border-radius: 50%;
            opacity: 0.4;
            animation: bounce 1.4s infinite ease-in-out both;
        }

        .typing-dot:nth-child(1) { animation-delay: -0.32s; }
        .typing-dot:nth-child(2) { animation-delay: -0.16s; }

        /* ── Side Console Panel ── */
        .side-panel {
            width: 420px;
            background: #111218;
            display: flex;
            flex-direction: column;
            padding: 30px;
            gap: 30px;
            overflow-y: auto;
        }

        .panel-section-title {
            font-family: 'Outfit', sans-serif;
            font-size: 16px;
            font-weight: 700;
            letter-spacing: 0.5px;
            text-transform: uppercase;
            color: var(--color-text-muted);
            margin-bottom: 15px;
            display: flex;
            align-items: center;
            justify-content: space-between;
        }

        /* Reset button */
        .action-btn {
            background: rgba(255, 255, 255, 0.03);
            border: 1px solid var(--border-glass);
            padding: 6px 12px;
            border-radius: 6px;
            color: var(--color-text-muted);
            font-size: 12px;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.2s ease;
        }

        .action-btn:hover {
            background: rgba(255, 255, 255, 0.08);
            color: var(--color-text-main);
        }

        /* Phone Screen Canvas Mockup */
        .phone-mockup-wrapper {
            margin: 0 auto;
            position: relative;
            width: 250px;
            height: 555px; /* Ratio based on 1260x2800 width/height ~ 0.45 */
            background: #000;
            border-radius: 36px;
            border: 8px solid #28293d;
            box-shadow: 0 10px 40px rgba(0,0,0,0.5), 0 0 0 1px rgba(255,255,255,0.05);
            overflow: hidden;
            display: flex;
            align-items: center;
            justify-content: center;
        }

        .phone-mockup-screen {
            position: relative;
            width: 100%;
            height: 100%;
            background: radial-gradient(circle at center, #1b1c2b 0%, #0d0e15 100%);
        }

        .phone-canvas {
            position: absolute;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            z-index: 10;
        }

        /* Simulated details inside phone mockup */
        .sim-status-bar {
            position: absolute;
            top: 0;
            left: 0;
            width: 100%;
            height: 20px;
            padding: 2px 14px;
            display: flex;
            justify-content: space-between;
            font-size: 9px;
            color: rgba(255,255,255,0.4);
            font-weight: 500;
            z-index: 5;
        }

        .sim-nav-bar {
            position: absolute;
            bottom: 0;
            left: 0;
            width: 100%;
            height: 30px;
            display: flex;
            justify-content: center;
            align-items: center;
            z-index: 5;
        }

        .sim-nav-pill {
            width: 80px;
            height: 4px;
            background: rgba(255,255,255,0.25);
            border-radius: 2px;
        }

        /* Telemetry box */
        .telemetry-card {
            background: var(--bg-surface-glass);
            border: 1px solid var(--border-glass);
            border-radius: 12px;
            padding: 16px;
            display: flex;
            flex-direction: column;
            gap: 12px;
        }

        .telemetry-row {
            display: flex;
            justify-content: space-between;
            font-size: 13px;
        }

        .telemetry-label {
            color: var(--color-text-muted);
        }

        .telemetry-value {
            font-family: 'JetBrains Mono', monospace;
            font-weight: 500;
            color: var(--color-text-main);
        }

        .telemetry-value.running {
            color: var(--status-success);
        }

        /* Animations */
        @keyframes float {
            0%, 100% { transform: translateY(0); }
            50% { transform: translateY(-4px); }
        }

        @keyframes hover-ghost {
            0%, 100% { transform: translateY(0) scale(1); }
            50% { transform: translateY(-8px) scale(1.03); }
        }

        @keyframes pulse-green {
            0% { box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.4); }
            70% { box-shadow: 0 0 0 8px rgba(16, 185, 129, 0); }
            100% { box-shadow: 0 0 0 0 rgba(16, 185, 129, 0); }
        }

        @keyframes flash-pulse {
            0%, 100% { opacity: 1; }
            50% { opacity: 0.6; }
        }

        @keyframes bounce {
            0%, 80%, 100% { transform: scale(0); }
            40% { transform: scale(1); }
        }
    </style>
</head>
<body>

    <!-- ── Header ── -->
    <header>
        <div class="logo-container">
            <span class="logo-icon">👻</span>
            <span class="logo-text">GHOST MACHINE</span>
        </div>
        <div class="device-status-badge">
            <div id="device-status-dot" class="status-dot"></div>
            <span id="device-status-text">Checking Telemetry...</span>
        </div>
    </header>

    <!-- ── Main Workspace ── -->
    <div class="main-container">
        
        <!-- ── Left: Chat ── -->
        <div class="chat-section">
            <div id="chat-messages" class="messages-container">
                <div id="empty-state" class="empty-state">
                    <div class="empty-ghost">👻</div>
                    <h3>Control Console Ready</h3>
                    <p>Enter any natural command to remotely execute actions on your Android device (e.g. <i>"Open calculator and calculate 20+1000"</i>)</p>
                </div>
            </div>
            
            <!-- Input Area -->
            <div class="input-section">
                <form id="command-form" class="input-wrapper" onsubmit="submitCommand(event)">
                    <input id="prompt-input" type="text" autocomplete="off" placeholder="Tell Ghost Machine what to do..." class="prompt-input" required>
                    <button type="submit" class="send-btn">
                        <svg viewBox="0 0 24 24"><line x1="22" y1="2" x2="11" y2="13"></line><polygon points="22 2 15 22 11 13 2 9 22 2"></polygon></svg>
                    </button>
                </form>
            </div>
        </div>

        <!-- ── Right: Visual Canvas & Telemetry ── -->
        <div class="side-panel">
            
            <!-- Section: Interactive 2D Screen canvas -->
            <div>
                <div class="panel-section-title">Visual Layout Preview</div>
                <div class="phone-mockup-wrapper">
                    <div class="phone-mockup-screen">
                        <!-- Simulated status bar and elements -->
                        <div class="sim-status-bar">
                            <span>00:42</span>
                            <span>📶 🔋 100%</span>
                        </div>
                        
                        <!-- Interactive coordinates canvas -->
                        <canvas id="phone-canvas" class="phone-canvas" width="234" height="539"></canvas>
                        
                        <div class="sim-nav-bar">
                            <div class="sim-nav-pill"></div>
                        </div>
                    </div>
                </div>
            </div>

            <!-- Section: Device Telemetry & Execution State -->
            <div>
                <div class="panel-section-title">
                    <span>Active Telemetry</span>
                    <button class="action-btn" onclick="resetSyncState()">Reset Sync</button>
                </div>
                <div class="telemetry-card">
                    <div class="telemetry-row">
                        <span class="telemetry-label">Active Connection</span>
                        <span id="telemetry-conn-state" class="telemetry-value">Disconnected</span>
                    </div>
                    <div class="telemetry-row">
                        <span class="telemetry-label">Active Device</span>
                        <span class="telemetry-value">iQOO I2221</span>
                    </div>
                    <div class="telemetry-row">
                        <span class="telemetry-label">Resolution</span>
                        <span class="telemetry-value">1260 x 2800</span>
                    </div>
                    <div class="telemetry-row">
                        <span class="telemetry-label">Execution Stage</span>
                        <span id="telemetry-stage" class="telemetry-value">-</span>
                    </div>
                    <div class="telemetry-row">
                        <span class="telemetry-label">Active Action</span>
                        <span id="telemetry-action" class="telemetry-value">-</span>
                    </div>
                </div>
            </div>

        </div>

    </div>

    <!-- Scripts -->
    <script>
        const chatContainer = document.getElementById('chat-messages');
        const emptyState = document.getElementById('empty-state');
        const promptInput = document.getElementById('prompt-input');
        const canvas = document.getElementById('phone-canvas');
        const ctx = canvas.getContext('2d');

        // Phone size configuration (maps 1260x2800 to actual canvas size 234x539)
        const DEV_WIDTH = 1260;
        const DEV_HEIGHT = 2800;

        let activeSteps = [];
        let executionStatus = { status: "IDLE", current_step: 0, total_steps: 0 };
        let knownMessageIds = new Set();

        // Canvas mapping helper
        function mapX(x) {
            return (x / DEV_WIDTH) * canvas.width;
        }
        function mapY(y) {
            return (y / DEV_HEIGHT) * canvas.height;
        }

        // Periodically poll backend for status and sync message logs
        async function checkTelemetry() {
            try {
                const response = await fetch('/execution-status');
                if (!response.ok) return;
                const data = await response.json();

                // 1. Update Connection Badges
                const isConnected = data.device.connected;
                const dot = document.getElementById('device-status-dot');
                const text = document.getElementById('device-status-text');
                const telemetryConn = document.getElementById('telemetry-conn-state');

                if (isConnected) {
                    dot.className = "status-dot connected";
                    text.innerText = "Phone connected via ADB reverse";
                    telemetryConn.innerText = "Connected";
                    telemetryConn.className = "telemetry-value running";
                } else {
                    dot.className = "status-dot disconnected";
                    text.innerText = "Waiting for phone connections...";
                    telemetryConn.innerText = "Disconnected";
                    telemetryConn.className = "telemetry-value";
                }

                // 2. Sync message history from backend
                if (data.messages && data.messages.length > 0) {
                    emptyState.style.display = 'none';
                    data.messages.forEach(msg => {
                        if (!knownMessageIds.has(msg.id)) {
                            addMessageToHistory(msg);
                            knownMessageIds.add(msg.id);
                        }
                    });
                }

                // 3. Update execution tracking
                const oldExec = executionStatus;
                executionStatus = data.execution;
                if (executionStatus && executionStatus.id) {
                    updateBubbleExecution(executionStatus);
                    updateTelemetryPanel(executionStatus);
                    
                    // Trigger canvas redraw on step transition
                    if (oldExec.current_step !== executionStatus.current_step || oldExec.status !== executionStatus.status) {
                        drawCanvasOverlay();
                    }
                }

            } catch (err) {
                console.error("Telemetry fetch error:", err);
            }
        }

        // Start polling telemetry loop
        setInterval(checkTelemetry, 1000);
        checkTelemetry();

        // Reset system states
        async function resetSyncState() {
            try {
                await fetch('/reset-sync', { method: 'POST' });
                await fetch('/reset', { method: 'POST' });
                // Reload UI list
                location.reload();
            } catch (err) {
                console.error("Reset error:", err);
            }
        }

        // Add a message into the scrollable UI
        function addMessageToHistory(msg) {
            const row = document.createElement('div');
            row.className = `message-row ${msg.sender === 'web' ? 'user' : 'ai'}`;
            row.id = `msg-${msg.id}`;

            const bubble = document.createElement('div');
            bubble.className = 'chat-bubble';

            if (msg.sender !== 'web') {
                const avatar = document.createElement('div');
                avatar.className = 'ai-avatar';
                avatar.innerText = '👻';
                bubble.appendChild(avatar);
            }

            const textNode = document.createElement('div');
            textNode.innerText = msg.sender === 'web' ? msg.command : msg.summary;
            bubble.appendChild(textNode);

            // If it is AI and contains steps, render the dynamic steps list!
            if (msg.sender !== 'web' && msg.steps && msg.steps.length > 0) {
                const list = document.createElement('div');
                list.className = 'chat-steps-list';
                list.id = `steps-list-${msg.id}`;

                msg.steps.forEach((step, idx) => {
                    const item = document.createElement('div');
                    item.className = 'chat-step-item';
                    item.id = `step-${msg.id}-${idx + 1}`;
                    item.onclick = () => focusStepCoordinate(step, idx);

                    const badge = document.createElement('div');
                    badge.className = 'step-num-badge';
                    badge.innerText = idx + 1;

                    const det = document.createElement('div');
                    det.className = 'step-details';

                    const tag = document.createElement('span');
                    tag.className = 'step-action-tag';
                    tag.innerText = step.action;

                    const reason = document.createElement('span');
                    reason.className = 'step-reason';
                    reason.innerText = step.reason;

                    det.appendChild(tag);
                    det.appendChild(reason);
                    item.appendChild(badge);
                    item.appendChild(det);
                    list.appendChild(item);
                });

                bubble.appendChild(list);

                // Add active executing chip
                const chip = document.createElement('div');
                chip.id = `chip-${msg.id}`;
                chip.className = 'status-chip idle';
                chip.innerText = 'Idle';
                bubble.appendChild(chip);

                // Cache active steps for canvas drawing
                activeSteps = msg.steps;
                drawCanvasOverlay();
            }

            const stamp = document.createElement('div');
            stamp.className = 'msg-timestamp';
            stamp.innerText = new Date(msg.timestamp * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
            bubble.appendChild(stamp);

            row.appendChild(bubble);
            chatContainer.appendChild(row);
            chatContainer.scrollTop = chatContainer.scrollHeight;
        }

        // Highlight step inside chat bubble
        function updateBubbleExecution(exec) {
            const chip = document.getElementById(`chip-${exec.id}`);
            if (!chip) return;

            chip.className = `status-chip ${exec.status.toLowerCase()}`;
            
            if (exec.status === "QUEUED") {
                chip.innerText = "⏳ Queued in system";
            } else if (exec.status === "DISPATCHED") {
                chip.innerText = "⚡ Dispatched to phone";
            } else if (exec.status === "EXECUTING") {
                chip.innerText = `⚙️ Executing step ${exec.current_step} of ${exec.total_steps}`;
            } else if (exec.status === "SUCCESS") {
                chip.innerText = "✅ Completed Successfully";
            } else if (exec.status === "FAILED") {
                chip.innerText = `❌ Failed at step ${exec.current_step}`;
            }

            // Sync item active class
            for (let i = 1; i <= exec.total_steps; i++) {
                const item = document.getElementById(`step-${exec.id}-${i}`);
                if (item) {
                    if (exec.status === "EXECUTING" && i === exec.current_step) {
                        item.className = "chat-step-item active";
                    } else if (i < exec.current_step || exec.status === "SUCCESS") {
                        item.className = "chat-step-item completed";
                    } else {
                        item.className = "chat-step-item";
                    }
                }
            }
        }

        // Update telemetry data card on sidebar
        function updateTelemetryPanel(exec) {
            const stageVal = document.getElementById('telemetry-stage');
            const actVal = document.getElementById('telemetry-action');

            if (exec.status === "IDLE") {
                stageVal.innerText = "-";
                actVal.innerText = "-";
            } else {
                stageVal.innerText = `${exec.status} (${exec.current_step}/${exec.total_steps})`;
                actVal.innerText = exec.current_action ? `[${exec.current_action}] ${exec.current_reason}` : "-";
            }
        }

        // Submit command from Web Console
        async function submitCommand(e) {
            e.preventDefault();
            const val = promptInput.value.trim();
            if (!val) return;

            promptInput.value = '';
            emptyState.style.display = 'none';

            // 1. Append dummy loading bubble
            const loaderRow = document.createElement('div');
            loaderRow.className = 'message-row ai';
            loaderRow.id = 'temp-loader';

            const bubble = document.createElement('div');
            bubble.className = 'chat-bubble';
            bubble.innerHTML = `
                <div class="ai-avatar">👻</div>
                <div class="typing-bubble">
                    <div class="typing-dot"></div>
                    <div class="typing-dot"></div>
                    <div class="typing-dot"></div>
                </div>
            `;
            loaderRow.appendChild(bubble);
            chatContainer.appendChild(loaderRow);
            chatContainer.scrollTop = chatContainer.scrollHeight;

            try {
                const res = await fetch('/next-action', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ command: val, sender: 'web' })
                });
                
                const data = await res.json();
                
                // Clear loading bubble
                const loader = document.getElementById('temp-loader');
                if (loader) loader.remove();

                // Wait for polling to detect the new message and insert it naturally

            } catch (err) {
                console.error("Submit error:", err);
                const loader = document.getElementById('temp-loader');
                if (loader) loader.innerHTML = '<div class="ai-avatar">👻</div><div style="color:var(--status-error)">Error calling Gemini backend</div>';
            }
        }

        // Focus and draw single step highlighting ring
        function focusStepCoordinate(step, idx) {
            drawCanvasOverlay();
            
            // Draw highlight overlay ring
            if (step.action === 'tap') {
                const cx = mapX(step.x);
                const cy = mapY(step.y);
                ctx.beginPath();
                ctx.arc(cx, cy, 22, 0, 2 * Math.PI);
                ctx.strokeStyle = 'rgba(255, 235, 59, 0.9)';
                ctx.lineWidth = 3;
                ctx.stroke();
            } else if (step.action === 'swipe') {
                const sx = mapX(step.startX);
                const sy = mapY(step.startY);
                const ex = mapX(step.endX);
                const ey = mapY(step.endY);
                
                ctx.beginPath();
                ctx.arc(sx, sy, 10, 0, 2*Math.PI);
                ctx.fillStyle = '#ffeb3b';
                ctx.fill();
            }
        }

        // Core visual screen canvas rendering engine
        function drawCanvasOverlay() {
            ctx.clearRect(0, 0, canvas.width, canvas.height);
            if (!activeSteps || activeSteps.length === 0) return;

            // Draw connecting lines between sequence coordinates
            ctx.beginPath();
            let first = true;
            activeSteps.forEach(step => {
                let x = 0, y = 0;
                if (step.action === 'tap') {
                    x = mapX(step.x);
                    y = mapY(step.y);
                } else if (step.action === 'swipe') {
                    x = mapX(step.startX);
                    y = mapY(step.startY);
                } else {
                    return; // Skip non-coordinate steps
                }

                if (first) {
                    ctx.moveTo(x, y);
                    first = false;
                } else {
                    ctx.lineTo(x, y);
                }
            });
            ctx.strokeStyle = 'rgba(99, 102, 241, 0.4)';
            ctx.lineWidth = 2;
            ctx.setLineDash([4, 4]);
            ctx.stroke();
            ctx.setLineDash([]); // Reset dash

            // Draw each step coordinate marker
            activeSteps.forEach((step, idx) => {
                const stepNum = idx + 1;
                const isCurrent = (executionStatus.status === "EXECUTING" && stepNum === executionStatus.current_step);
                const isDone = (stepNum < executionStatus.current_step || executionStatus.status === "SUCCESS");

                if (step.action === 'tap') {
                    const cx = mapX(step.x);
                    const cy = mapY(step.y);

                    // Background pulse for active
                    if (isCurrent) {
                        ctx.beginPath();
                        ctx.arc(cx, cy, 18, 0, 2 * Math.PI);
                        ctx.fillStyle = 'rgba(99, 102, 241, 0.35)';
                        ctx.fill();
                    }

                    ctx.beginPath();
                    ctx.arc(cx, cy, 10, 0, 2 * Math.PI);
                    ctx.fillStyle = isDone ? 'rgba(16, 185, 129, 0.95)' : (isCurrent ? '#6366f1' : 'rgba(255,255,255,0.15)');
                    ctx.strokeStyle = isCurrent ? '#ffffff' : 'rgba(255,255,255,0.4)';
                    ctx.lineWidth = 1.5;
                    ctx.fill();
                    ctx.stroke();

                    // Step Index Text
                    ctx.fillStyle = isDone || isCurrent ? '#ffffff' : 'rgba(255,255,255,0.7)';
                    ctx.font = 'bold 9px sans-serif';
                    ctx.textAlign = 'center';
                    ctx.textBaseline = 'middle';
                    ctx.fillText(stepNum, cx, cy);

                } else if (step.action === 'swipe') {
                    const sx = mapX(step.startX);
                    const sy = mapY(step.startY);
                    const ex = mapX(step.endX);
                    const ey = mapY(step.endY);

                    // Draw Swipe Arrow Path
                    ctx.beginPath();
                    ctx.moveTo(sx, sy);
                    ctx.lineTo(ex, ey);
                    ctx.strokeStyle = isDone ? '#10b981' : (isCurrent ? '#6366f1' : 'rgba(255,255,255,0.4)');
                    ctx.lineWidth = isCurrent ? 3 : 2;
                    ctx.stroke();

                    // Arrow tip
                    const angle = Math.atan2(ey - sy, ex - sx);
                    ctx.beginPath();
                    ctx.moveTo(ex, ey);
                    ctx.lineTo(ex - 8 * Math.cos(angle - Math.PI/6), ey - 8 * Math.sin(angle - Math.PI/6));
                    ctx.lineTo(ex - 8 * Math.cos(angle + Math.PI/6), ey - 8 * Math.sin(angle + Math.PI/6));
                    ctx.closePath();
                    ctx.fillStyle = isDone ? '#10b981' : (isCurrent ? '#6366f1' : 'rgba(255,255,255,0.4)');
                    ctx.fill();

                    // Step badge at start coordinate
                    ctx.beginPath();
                    ctx.arc(sx, sy, 8, 0, 2 * Math.PI);
                    ctx.fillStyle = isDone ? 'rgba(16, 185, 129, 0.95)' : (isCurrent ? '#6366f1' : 'rgba(255,255,255,0.15)');
                    ctx.fill();
                    
                    ctx.fillStyle = '#ffffff';
                    ctx.font = 'bold 8px sans-serif';
                    ctx.textAlign = 'center';
                    ctx.textBaseline = 'middle';
                    ctx.fillText(stepNum, sx, sy);
                }
            });
        }
    </script>
</body>
</html>
"""
