# NEXORA Documentation Hub

Welcome to the official documentation repository for **NEXORA**, a decentralized, off-grid peer-to-peer (P2P) mesh communication and emergency response platform for Android.

---

## 📚 Documentation Directory

All technical specifications, architectural designs, protocols, and guides are organized below:

| Document | Description |
|---|---|
| 🏛️ **[System Architecture](arch.md)** | Full architectural design document covering C4 diagrams (Level 1, 2, 3), clean architecture layers, Room SQLite database schemas, AES-256 encryption, and concurrency models. |
| 🌐 **[Mesh Protocol Specification](mesh.md)** | Comprehensive technical breakdown of the multi-hop mesh network, packet structure, hop limits ($H \le 3$), delay-tolerant networking (DTN), route tables, and hybrid transports (Google Nearby Connections + Wi-Fi Direct). |
| 🔌 **[Wire Protocol & Additive Compatibility](wire_compat.md)** | Low-level payload wire framing, location frames (`[LOC]:`, `[LOC_LIVE]:`), and alert extension formatting standards. |
| 🛠️ **[Engineering & Build Workflow](workflow.md)** | Developer workflow manual, Gradle build tasks, APK generation, keystore signing, ADB debugging commands, Logcat telemetry tags, and multi-device mesh test matrices. |
| 🤝 **[Contribution Guidelines](contribute.md)** | Contributor guide, local environment setup, Android Studio requirements, code conventions, Git branching strategy, Conventional Commits, and Pull Request process. |

---

## 🚀 Quick Navigation
- For the main project overview, see the root **[`README.md`](../README.md)**.
