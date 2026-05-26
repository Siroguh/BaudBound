<div align="center">
  <img src="assets/logo.svg" alt="BaudBound" width="320"/>
  <br/><br/>

  <p>A lightweight event-driven automation engine that maps serial port data to system actions.</p>

  ![Java](https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=openjdk&logoColor=white)
  ![Maven](https://img.shields.io/badge/Maven-Build-blue?style=for-the-badge&logo=apachemaven&logoColor=white)
  ![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20Linux%20%7C%20macOS-lightgrey?style=for-the-badge)
  ![License](https://img.shields.io/badge/License-MIT-green?style=for-the-badge)
  ![Version](https://img.shields.io/github/v/release/NATroutter/BaudBound?style=for-the-badge&color=teal&label=Version)

</div>

---

## What is BaudBound?

BaudBound listens to serial devices and fires configurable actions whenever incoming data matches your conditions. Hook up any serial device — a microcontroller, a barcode scanner, a custom sensor — and turn its output into webhooks, launched programs, opened URLs, simulated keystrokes, file writes, notifications, and more.

## Features

### Serial devices
- Connect to **multiple devices simultaneously**, each with independent port, baud rate, data bits, stop bits, parity, and flow control settings
- **Auto-connect** on startup and **auto-reconnect** if a device is unplugged
- **Send data back** to a connected device as an action

### Conditions (16 types)
- **Text** — starts with, ends with, contains, not contains, not starts with, equals
- **Pattern** — full regular expression match
- **Numeric** — is numeric, greater than, less than, between (min,max)
- **Length** — input length equals
- **State** — state equals, state not equals, state is empty
- **Device** — device equals, device not equals (filter by source device)

### Actions (12 types)
- **Call webhook** — HTTP GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS with custom headers, body, durable delivery, acknowledgement checks, WebSocket ACK support, retries, and input preprocessing
- **Open URL** — open in the default system browser
- **Launch program** — with optional arguments and Run As Admin
- **Type text** — simulate keyboard input via clipboard paste
- **Copy to clipboard** — place text on the clipboard without pasting
- **Show notification** — system-tray balloon (INFO / WARNING / ERROR)
- **Write to file** — overwrite a file on each trigger
- **Append to file** — append a line to a file on each trigger
- **Play sound** — play a .wav file, or system beep if no path given
- **Send to device** — write a string to a connected serial device
- **Set state / Clear state** — manage named state variables

### Events & logic
- **Multiple conditions and actions** per event
- **Variable substitution** — use `{input}`, `{timestamp}`, and `{delivery.id}` in webhook values; optional URL-encoding for webhooks
- **State machine** — set and check named state variables to build multi-step flows
- **Event ordering** — reorder events and control whether all matches fire or only the first
- **Condition-first sorting** — optionally evaluate conditional events before unconditional ones

### Application
- **System tray** — minimize to tray, start hidden, double-click to restore
- **Headless mode** (`--nogui`) — full serial processing with no window or tray icon
- **OS autostart** — register on login via Windows registry, macOS LaunchAgent, or Linux `.desktop` file
- **Shortcut creation** — create a shortcut to BaudBound in any folder
- **CLI flags** — `--hidden`, `--debug`, `--nogui`, `--version`
- **Auto-updater** — background update check with in-app download and restart
- **Debug overlay** — real-time FPS, memory, JVM, and device status panel

## Screenshots

<div align="center">
  <img src="assets/screenshot_4.png" alt="Main window" width="49%"/>
  <img src="assets/screenshot_2.png" alt="Settings" width="49%"/>
  <img src="assets/screenshot_5.png" alt="Webhooks list" width="49%"/>
  <img src="assets/screenshot_3.png" alt="Create webhook" width="49%"/>
  <img src="assets/screenshot_1.png" alt="Create program" width="49%"/>
</div>

## Installation

Download the latest release from the [Releases](../../releases) page and run:

```bash
java -jar BaudBound.jar
```

Java 21 or newer is required.

## Building from source

```bash
git clone https://github.com/NATroutter/BaudBound.git
cd BaudBound
mvn package
java -jar target/BaudBound.jar
```

## Development builds

Development builds are available via Jenkins and the Maven repository:

- **Jenkins:** https://jenkins.nat.gg/job/general/job/BaudBound/
- **Maven repository:** https://repo.nat.gg/

## Quick start

1. Open **Devices** and add your serial device (port, baud rate, etc.)
2. Go to **Actions → Webhooks** or **Actions → Programs** to set up reusable actions
3. Create an **Event** — add conditions to filter incoming data and actions to run when they match
4. Enable auto-connect on the device, or click the connect button — BaudBound will start listening

## Configuration

All settings are saved automatically to a JSON file in the platform config directory:
- **Windows** — `%APPDATA%\BaudBound\storage.json`
- **macOS** — `~/Library/Application Support/BaudBound/storage.json`
- **Linux** — `~/.config/BaudBound/storage.json`

No manual editing required.

## Author

Made by [NATroutter](https://natroutter.fi)

---

<div align="center">
  <img src="assets/logo-notext.svg" alt="BaudBound" width="64"/>
</div>
