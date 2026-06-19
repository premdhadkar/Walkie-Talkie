# Wi-Fi Direct Walkie-Talkie

A robust, peer-to-peer walkie-talkie application for Android that uses Wi-Fi Direct (P2P) to establish communication between devices without the need for an internet connection or a traditional router. This project provides a fully functional push-to-talk (PTT) interface, real-time voice streaming, and dynamic mesh-like routing features for local device networks.

## Overview

The Wi-Fi Direct Walkie-Talkie app leverages Android's Wi-Fi P2P capabilities to allow users to discover, connect, and communicate with nearby devices. It integrates Service Discovery (DNS-SD) to effortlessly find devices running the app, establishing a direct connection and creating a real-time, low-latency audio stream using standard Android audio APIs (`AudioRecord`, `AudioTrack`) over TCP sockets. 

With recent updates, it now supports a **Hub implementation** (Mesh Routing), where a central Group Owner can forward audio chunks between multiple connected peers, making it suitable for multi-device communication.

## Features

- **Internet-Free Communication:** Relies entirely on Wi-Fi Direct. No cellular data, internet connection, or external routers are needed.
- **Push-to-Talk (PTT) Interface:** A familiar, intuitive push-to-talk button built with Jetpack Compose.
- **Service Discovery (DNS-SD):** Automatically scans and identifies other nearby devices running the Walkie-Talkie app.
- **Group Owner Hub & Mesh Routing:** The group owner acts as a central hub, relaying voice data to all connected clients.
- **Real-Time Audio Streaming:** High-quality 16kHz PCM 16-bit audio streaming over TCP sockets.
- **Signal Quality Monitoring:** Live feedback on connection strength and signal quality (Excellent, Good, Poor).
- **Background Service Support:** Maintains the connection and listens for audio even when the app is in the background (using Foreground Services).

## Requirements

- **Android Device:** Android 8.0 (API 26) or higher.
- **Hardware:** Wi-Fi Direct (P2P) support.
- **Permissions:**
  - `NEARBY_WIFI_DEVICES` (For Android 13+)
  - `RECORD_AUDIO` (For microphone access)
  - `POST_NOTIFICATIONS` (For Foreground Service notification)
  - Location permissions (For older Android versions to scan for Wi-Fi devices)

## Getting Started

1. Clone this repository.
2. Open the project in Android Studio.
3. Build and run the app on at least two different Android physical devices (Wi-Fi Direct does not work on emulators).
4. Grant the required permissions.
5. Once the devices discover each other, tap on a device to connect.
6. Press and hold the **Hold to Talk** button to speak.

For more deep-dive technical insights, please check out [TECHNICAL_DETAILS.md](TECHNICAL_DETAILS.md).
