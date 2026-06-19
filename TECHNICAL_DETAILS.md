# Technical Details & Architecture

This document outlines the technical underpinnings of the Wi-Fi Direct Walkie-Talkie application.

## Core Technologies

- **Language:** Kotlin
- **UI Framework:** Jetpack Compose (Material 3)
- **Networking:** Android Wi-Fi P2P (`WifiP2pManager`), DNS-SD, Java Sockets (TCP)
- **Audio Processing:** `AudioRecord` (for mic input), `AudioTrack` (for playback), `MediaPlayer` (for UI sounds)
- **Concurrency:** Kotlin Coroutines & Flows

## Architecture Components

### 1. `WifiDirectManager`
This class encapsulates the Wi-Fi P2P API.
- **Service Discovery:** It broadcasts a local DNS-SD service (`_walkietalkie._tcp`) so that devices can uniquely identify each other as running this specific application, separating them from other random Wi-Fi Direct devices nearby.
- **Connection Handling:** Handles the connection intent. For Android 10+ (API 29+), it utilizes `WifiP2pConfig.Builder` to explicitly set the group operating band and device address. For older versions, it uses the standard intent mechanism.
- **State Management:** Uses Kotlin `StateFlow` to expose the list of peers (`peers`), discovered app users (`appPeers`), and the current connection status (`isConnected`) to the UI.

### 2. `AudioStreamer`
The heart of the voice transmission system.
- **Audio Configuration:** Captures audio using `AudioRecord` at a 16kHz sample rate, 16-bit PCM, Mono channel. Playback is handled synchronously via `AudioTrack`.
- **Socket Communication:**
  - **Server (Group Owner):** Listens on port 8888. It can accept multiple socket connections, keeping track of them in a `CopyOnWriteArrayList<PeerConnection>`.
  - **Client:** Connects to the Group Owner's IP address on port 8888.
- **Mesh/Hub Routing:** When the Server (Group Owner) receives an audio chunk from one client, it immediately broadcasts that chunk to all other active connections. This creates a star-topology mesh network where the Group Owner acts as the hub.
- **Ping/Latency Checking:** Each connection runs a continuous ping job to calculate latency. Based on the latency (<100ms, <300ms, or higher), the app determines the signal quality.

### 3. `WalkieTalkieService`
A Foreground Service ensuring the connection remains alive when the user navigates away from the app.
- Maintains the lifecycle of `WifiDirectManager` and `AudioStreamer`.
- Listens to Android Broadcasts (`WIFI_P2P_STATE_CHANGED_ACTION`, `WIFI_P2P_PEERS_CHANGED_ACTION`, `WIFI_P2P_CONNECTION_CHANGED_ACTION`) and delegates them to the manager.
- Shows a persistent notification to the user indicating the app is active and running in the background.

### 4. `MainActivity` & UI
- **Jetpack Compose:** Completely UI-driven by Compose.
- **Permissions:** Manages the modern Android permission model, including the new `NEARBY_WIFI_DEVICES` permission introduced in Android 13 (Tiramisu).
- **Push-to-Talk (PTT):** Utilizes `pointerInteropFilter` to intercept raw touch events (`ACTION_DOWN`, `ACTION_UP`, `ACTION_CANCEL`), triggering `AudioStreamer.startRecording()` and `stopRecording()` respectively.

## Network Topology

The app dynamically sets up a **Star Topology**:
1. One device becomes the **Group Owner (Hub)** during the Wi-Fi Direct negotiation phase.
2. All other devices become **Clients**.
3. Clients transmit their audio data exclusively to the Hub.
4. The Hub plays the audio locally and simultaneously relays (broadcasts) the audio bytes to all other connected Clients.

## Future Improvements

- Support for UDP streams (RTP) instead of TCP to reduce latency and prevent head-of-line blocking during poor network conditions.
- Noise cancellation and echo suppression integrations.
- Background scanning optimizations to save battery life.
