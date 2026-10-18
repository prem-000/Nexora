# NEXORA (PeerConnect) — Decentralized Off-Grid Mesh Communication

> **Package:** `com.fury.peerconnect`  
> **Application Label:** `NEXORA`  
> **Target Platform:** Android (Compile SDK: 35, Target SDK: 35, Min SDK: 24)  
> **Network Mode:** Decentralized Off-Grid Multi-Hop Mesh Network  
> **Master Technical Specification:** [`../README.md`](../README.md) (Complete Implementation & Academic Source)

---

## 1. Executive Summary

**NEXORA** is a decentralized, off-grid peer-to-peer (P2P) mesh communication and emergency response Android application. It functions entirely without cellular network connectivity, Wi-Fi routers, or central cloud infrastructure.

Using a **Hybrid Dual-Transport Engine** combining **Google Nearby Connections (P2P Cluster)** and **Native Wi-Fi Direct (Wi-Fi P2P) with TCP socket multiplexing**, NEXORA forms self-healing ad-hoc mesh clusters. Devices discover each other locally, synchronize routing tables via multi-hop topology broadcasts, relay messages across intermediary nodes (up to a 3-hop ceiling), and utilize Delay-Tolerant Networking (DTN) store-and-forward custody for offline delivery.

For the comprehensive, 28-chapter master technical source including complete sequence diagrams, database schemas, cryptographic models, and academic report mappings, refer to the root [**README.md**](../README.md).

---

## 2. Technology Stack & Core Components

| Layer | Technologies & Libraries | Key Components |
|---|---|---|
| **UI & Presentation** | Material Design 3, AndroidX, ViewBinding | `MainActivity`, `PeerAdapter`, `ChatAdapter`, `AlertAdapter`, `RecentActivityAdapter`, `ConnectedPeerAdapter`, `AvatarPickerAdapter`, `PeerSharedMediaAdapter` |
| **P2P Transport** | Google Nearby Connections API (`P2P_CLUSTER`), Android Wi-Fi Direct (`WifiP2pManager`) | `NearbyMeshTransport`, `WifiP2pMeshTransport`, `WifiP2pMeshManager`, `TcpTransportManager` (Port `8888`), `ConnectionManager` |
| **Mesh Routing & DTN** | Ad-hoc multi-hop routing engine, Custody store | `RoutingEngine`, `RouteTable`, `RouteEntry`, `DuplicateSuppressionCache`, `AlertCustodyStore` |
| **Security & Encryption** | Java Cryptography Extension (JCE), AES-256, Ed25519 | `SecurityHelper` (`AES/CBC/PKCS5Padding`, 256-bit symmetric key, Ed25519/ECDSA digital signatures) |
| **Local Persistence** | AndroidX Room ORM (v12), SQLite, SharedPreferences | `AppDatabase`, `PeerDao`, `MessageDao`, `AlertDao`, `UserManager` (`PeerConnectPrefs`), `TileCacheDbHelper` |
| **Tactical Map Engine** | MapLibre GL Native Android, Embedded NanoHTTPD | `OfflineMapManager`, `LocalTileServer` (`127.0.0.1`), `MapTilePrefetcher`, `OsrmRouteEngine`, `DistanceEngine` |
| **File & Attachment I/O**| Android Storage Access Framework (SAF), AndroidX FileProvider | `FileStorageManager`, `MeshBlobStore`, `BlobExchange`, `FileProvider` (`com.fury.peerconnect.fileprovider`) |
| **Asynchronous Engine** | Kotlin Coroutines, Java Concurrency (`ExecutorService`, `ConcurrentHashMap`, `AtomicBoolean`) | `Dispatchers.IO`, `LifecycleCoroutineScope` |

---

## 3. Documentation Directory

All technical specifications, architectural designs, protocols, and workflows are organized below:

| Specification | Document | Description |
|---|---|---|
| 📘 **Master Specification** | [`../README.md`](../README.md) | Authoritative technical master source for academic reports, system architecture, 106 unit tests, workflows, and protocols. |
| 🏛️ **System Architecture** | [`arch.md`](arch.md) | Full architectural design document covering C4 diagrams (Level 1, 2, 3), clean architecture layers, Room SQLite database schemas, AES-256 encryption, and concurrency models. |
| 🌐 **Mesh Protocol** | [`mesh.md`](mesh.md) | Comprehensive technical breakdown of the multi-hop mesh network, packet structure, hop limits ($H \le 3$), delay-tolerant networking (DTN), route tables, and hybrid transports. |
| 🔌 **Wire Compatibility** | [`wire_compat.md`](wire_compat.md) | Low-level payload wire framing, location frames (`[LOC]:`, `[LOC_LIVE]:`), and alert extension formatting standards. |
| 🛠️ **Engineering Workflow** | [`workflow.md`](workflow.md) | Developer workflow manual, Gradle build tasks, APK generation, keystore signing, ADB debugging commands, Logcat telemetry tags, and multi-device mesh test matrices. |
| 🤝 **Contribution Guide** | [`contribute.md`](contribute.md) | Contributor guide, local environment setup, Android Studio requirements, code conventions, Git branching strategy, Conventional Commits, and Pull Request process. |
