# Contributing to NEXORA

Thank you for your interest in contributing to **NEXORA**! NEXORA is a mission-critical, off-grid peer-to-peer mesh communications platform designed for disaster response and resilient local networking. Every contribution helps make emergency communications more dependable.

---

## 1. Code of Conduct

We are committed to providing a welcoming, inclusive, and harassment-free environment for all contributors. Please treat fellow contributors with respect, empathy, and professional courtesy at all times.

---

## 2. Development Setup

### 2.1 Prerequisites
- **Operating System:** Windows 10/11, macOS (Apple Silicon or Intel), or Linux.
- **Java Development Kit:** JDK 17 (OpenJDK 17 recommended).
- **Android Studio:** Android Studio Ladybug / Meerkat (2024.2+) or newer.
- **Android SDK:**
  - Android SDK Platform 36 (Android 16 API Preview / Final)
  - Minimum SDK: API 24 (Android 7.0 Nougat)
  - Android SDK Build-Tools 36.0.0
  - Android NDK (optional, for low-level socket profiling)
- **Hardware (Recommended):** At least two physical Android devices with Wi-Fi and Bluetooth enabled for real-world P2P mesh testing.

### 2.2 Getting the Code
```bash
git clone https://github.com/prem-000/NexorA.git
cd NexorA
```

### 2.3 Opening in Android Studio
1. Launch Android Studio.
2. Select **Open** and choose the `nexora` root directory.
3. Allow Gradle to sync dependencies automatically.
4. Verify your local `local.properties` contains your Android SDK location:
   ```properties
   sdk.dir=C:\\Users\\<YourUsername>\\AppData\\Local\\Android\\Sdk
   ```

---

## 3. Project Structure

```
nexora/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/fury/peerconnect/
│   │   │   │   ├── data/             # Room DB entities, DAOs, Database definition
│   │   │   │   ├── logic/            # File streaming, tactical map helpers
│   │   │   │   ├── network/          # Mesh routing, transports, socket managers
│   │   │   │   │   ├── manager/      # Connection & neighbor state management
│   │   │   │   │   ├── model/        # MeshMessage, MessageType, PeerState
│   │   │   │   │   ├── routing/      # RoutingEngine, RouteTable, DTN Custody
│   │   │   │   │   └── transport/    # Nearby Connections & Wi-Fi Direct implementations
│   │   │   │   └── ui/               # MainActivity, Adapters, Dialogs
│   │   │   └── res/                  # XML layouts, drawables, colors, strings
│   │   └── test/                     # Unit tests (AlertAttachmentPipelineTest, etc.)
│   └── build.gradle.kts              # App-level Gradle build configuration
├── docs/                             # Architecture, mesh protocol, workflows
└── build.gradle.kts                  # Root project build configuration
```

---

## 4. Development & Coding Guidelines

### 4.1 Kotlin & Architecture Standards
- **Clean Separation of Concerns:** Keep UI logic inside `ui/`, network packet handling inside `network/routing/`, transport abstraction in `network/transport/`, and database access in `data/`.
- **Coroutines & Thread Safety:**
  - Never execute network sockets, file I/O, or database queries on the Main Thread.
  - Always dispatch background tasks to `Dispatchers.IO`.
  - Always use thread-safe collections (`ConcurrentHashMap`, `CopyOnWriteArrayList`, `AtomicBoolean`) for shared network tables.
- **Fail-Safe Mesh Operations:** A malformed incoming packet or network timeout must **never** crash the application. Always handle deserialization and network stream exceptions gracefully with structured logging.
- **Resource Management:** Close all `Socket`, `InputStream`, `OutputStream`, and `Cursor` instances within `use { ... }` blocks or structured `finally` blocks.

### 4.2 Code Formatting
Follow the standard Kotlin style guide. Run formatting before committing:
```bash
./gradlew lint
```

---

## 5. Git Workflow & Branching Strategy

We follow the standard Git Feature-Branch workflow:

### 5.1 Branch Naming Conventions
- `feature/<feature-name>`: For new capabilities (e.g., `feature/bluetooth-le-audio-codec`)
- `fix/<bug-name>`: For bug fixes (e.g., `fix/route-expiry-liveness`)
- `docs/<doc-name>`: For documentation updates (e.g., `docs/mesh-protocol-update`)
- `refactor/<module-name>`: For non-breaking code restructuring

### 5.2 Commit Message Format
We adhere to [Conventional Commits](https://www.conventionalcommits.org/):
```
<type>(<scope>): <short summary>

[optional body]

[optional footer(s)]
```

**Types:**
- `feat`: A new feature for the user or network layer
- `fix`: A bug fix in routing, transport, or UI
- `docs`: Documentation changes only
- `style`: Formatting, missing semicolons, whitespace changes
- `refactor`: Code changes that neither fix a bug nor add a feature
- `perf`: Code changes that improve performance or reduce memory footprint
- `test`: Adding or correcting tests

**Examples:**
```bash
git commit -m "feat(routing): decrease route expiration TTL to 35 seconds"
git commit -m "fix(ui): reset peer status when explicit disconnect occurs"
git commit -m "docs(mesh): add packet framing sequence diagram"
```

---

## 6. Testing Requirements

All contributions must pass existing tests and include new tests when applicable:

### 6.1 Running Unit Tests
```bash
# Windows
gradlew.bat test

# macOS / Linux
./gradlew test
```

### 6.2 Manual Multi-Device Verification
For mesh network or transport modifications, test with **at least two physical devices**:
1. Install the debug build on both devices (`./gradlew installDebug`).
2. Turn off Cellular Data and standard Wi-Fi router connections on both phones.
3. Launch NEXORA on both devices.
4. Verify peer discovery, direct messaging, file transfers, and SOS alert propagation across devices.

---

## 7. Submitting a Pull Request (PR)

1. Push your branch to your GitHub fork:
   ```bash
   git push origin feature/your-feature-name
   ```
2. Navigate to [prem-000/NexorA](https://github.com/prem-000/NexorA) on GitHub.
3. Click **Compare & pull request**.
4. Fill out the PR template with:
   - Clear description of the change.
   - Associated issue number (if applicable).
   - Steps taken to test on physical devices or emulators.
5. Wait for automated CI checks and maintainer review. Address any requested changes promptly.
