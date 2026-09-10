# NEXORA — System Architecture & Technical Design

> **Package Identifier:** `com.fury.peerconnect`  
> **Application Name:** NEXORA  
> **Target Platform:** Android (Min SDK: 24, Target SDK: 36 / Android 16)  
> **Architecture Pattern:** Clean Architecture + Reactive MVVM / Event-Driven Mesh  

---

## 1. Architectural Philosophy

NEXORA is engineered to be **resilient, decentralized, and zero-trust**. In emergency situations, traditional client-server assumptions (DNS, centralized authentication, cloud databases, REST APIs) completely fail. NEXORA replaces centralized cloud infrastructure with **local peer orchestration, multi-hop routing, and distributed local storage**.

```mermaid
flowchart TB
    subgraph UI ["Presentation Layer (UI)"]
        MA["MainActivity"]
        DASH["Dashboard View"]
        PEERS["Peers & Chat View"]
        ALERTS["Alerts & SOS View"]
        MAP["Tactical Offline Map"]
    end

    subgraph Service ["Domain & Application Logic"]
        UM["UserManager (Identity)"]
        SEC["SecurityHelper (AES-256)"]
        FSM["FileStorageManager"]
        BLOB["BlobExchange (Chunked Sync)"]
    end

    subgraph Routing ["Mesh Routing & DTN Engine"]
        RE["RoutingEngine"]
        RT["RouteTable"]
        DSC["DuplicateSuppressionCache"]
        ACS["AlertCustodyStore"]
    end

    subgraph Transport ["Transport Abstraction Layer"]
        NMT["NearbyMeshTransport (Nearby Connections)"]
        WMT["WifiP2pMeshTransport (Wi-Fi Direct)"]
        TCP["TcpTransportManager (Port 8888)"]
    end

    subgraph Storage ["Local Persistence Layer (Room SQLite)"]
        DB[("AppDatabase")]
        PDAO["PeerDao"]
        MDAO["MessageDao"]
        ADAO["AlertDao"]
    end

    UI --> Service
    UI --> Routing
    UI --> Storage
    Service --> Storage
    Routing --> Transport
    Routing --> Storage
```

---

## 2. C4 Architectural Views

### 2.1 C4 Context (Level 1)
```mermaid
C4Context
    title Level 1: System Context Diagram for NEXORA
    Person(rescuerA, "Field Rescuer (Node A)", "Operates smartphone in disaster zone")
    Person(relayNode, "Evacuee (Relay Node B)", "Passive intermediary node forwarding packets")
    Person(commandNode, "Base Station (Node C)", "Receives incoming distress beacons")

    System(appA, "NEXORA Instance A", "Android App (Local Mesh Endpoint)")
    System(appB, "NEXORA Instance B", "Android App (Mesh Relay)")
    System(appC, "NEXORA Instance C", "Android App (Monitoring Station)")

    Rel(rescuerA, appA, "Sends SOS alert & coordinates")
    Rel(relayNode, appB, "Forwards multi-hop packets")
    Rel(commandNode, appC, "Monitors peer statuses & alerts")

    Rel(appA, appB, "Wi-Fi Direct / BLE Nearby Radio Links")
    Rel(appB, appC, "Wi-Fi Direct / BLE Nearby Radio Links")
```

### 2.2 C4 Container (Level 2)
```mermaid
C4Container
    title Level 2: Container Diagram for NEXORA Android Client
    Container_Boundary(nexora_app, "NEXORA Android Client") {
        Component(ui_container, "UI Components", "Activity, Adapters, Views", "Provides interface for chats, maps, emergency SOS, and active peer list")
        Component(mesh_engine, "Mesh Engine", "RoutingEngine, RouteTable", "Evaluates hops, manages routing table, enforces TTL, loop suppression")
        Component(transport_container, "Transport Adapters", "Nearby & Wi-Fi Direct APIs", "Maintains sockets, runs TCP servers on :8888, broadcasts beacons")
        ComponentDb(sqlite_db, "Room SQLite Database", "AppDatabase", "Stores peers, messages, delivery states, and broadcast alerts")
        Component(security_module, "Security & Crypto", "Java Cryptography Extension", "Handles AES-256 encryption, IV initialization, and verification")
    }

    Rel(ui_container, mesh_engine, "Dispatches messages, observes status changes")
    Rel(mesh_engine, transport_container, "Sends serialized bytes over physical radio")
    Rel(mesh_engine, sqlite_db, "Persists route reachability, message delivery status")
    Rel(transport_container, mesh_engine, "Delivers incoming packets for processing")
    Rel(mesh_engine, security_module, "Encrypts and decrypts message bodies")
```

---

## 3. Core Subsystems

### 3.1 Presentation Layer (`com.fury.peerconnect.ui`)
- **Single Activity Architecture:** `MainActivity` coordinates four primary interfaces:
  - **Dashboard:** Network topology summary, active connection counts, live telemetry.
  - **Peers / Chat:** Direct messaging threads, file attachment sharing, online indicators.
  - **Alerts Feed:** Broadcast channel for SOS alerts, filtering chips (All, Medical, Fire, Lost Person).
  - **Tactical Map:** Offline grid preview with live peer locations and beacon coordinates.
- **Adapters:** Highly optimized `RecyclerView` adapters (`PeerAdapter`, `ChatAdapter`, `AlertAdapter`, `ConnectedPeerAdapter`, `RecentActivityAdapter`) with differential list updates.

### 3.2 Routing & Mesh Engine (`com.fury.peerconnect.network.routing`)
- **`RoutingEngine`**: The heart of packet delivery. Dispatches unicast and broadcast payloads, manages delivery confirmations (`ACK`), evaluates hop limits, and interacts with custody stores.
- **`RouteTable` & `RouteEntry`**: Keeps active paths to indirect nodes. Contains next-hop address, hop count (1 to 3), and expiration timestamps (35s TTL).
- **`DuplicateSuppressionCache`**: In-memory bounded cache preventing packet multiplication loops.
- **`AlertCustodyStore`**: Manages delay-tolerant DTN storage for offline delivery replication.

### 3.3 Transport Abstraction Layer (`com.fury.peerconnect.network.transport`)
- **`NearbyMeshTransport`**: Interfaces with Google Play Services Nearby Connections (`P2P_CLUSTER`).
- **`WifiP2pMeshTransport` & `WifiP2pMeshManager`**: Operates Android's native Wi-Fi P2P framework, creating autonomous groups and managing IP/port socket connections.
- **`TcpTransportManager`**: High-performance socket server listening on port `8888`. Handles raw binary stream framing for multi-megabyte file transfers.

### 3.4 Data Persistence Layer (`com.fury.peerconnect.data`)
- **Room ORM Database (`AppDatabase`)**:
  - `PeerEntity`: Tracks known peer usernames, direct endpoint IDs, reachability status, and hop distance.
  - `MessageEntity`: Local message history, sender/receiver IDs, timestamps, delivery state (`PENDING`, `SENT`, `DELIVERED`), and local file URIs.
  - `AlertEntity`: Emergency broadcast records, originators, coordinates, urgency severity, and read flags.

---

## 4. Cryptographic Security Model

NEXORA enforces zero-knowledge privacy for intermediate relay nodes:

```mermaid
sequenceDiagram
    participant Origin as Originator Node A
    participant Relay as Relay Node B
    participant Dest as Destination Node C

    Origin->>Origin: Encrypt body: AES-256(plaintext, symmetricKey)
    Origin->>Relay: Forward MeshMessage(payload=Ciphertext)
    Note over Relay: Relay inspects destinationPeerId = "Node C"<br/>Relay CANNOT decrypt payload
    Relay->>Dest: Forward MeshMessage(payload=Ciphertext)
    Dest->>Dest: Decrypt body: AES-256-Decrypt(payload, symmetricKey)
```

1. **Payload Secrecy:** Only the source and final recipient possess the capability to decrypt chat contents and sensitive alert coordinates.
2. **Intermediate Node Privacy:** Intermediate hops read only metadata necessary for forwarding: `messageId`, `destinationPeerId`, and `hopCount`.

---

## 5. Threading & Concurrency Model

- **Background Dispatchers:** All network I/O, socket reads, Room database transactions, and cryptographic operations run exclusively on `Dispatchers.IO` using Kotlin Coroutines.
- **UI Thread Isolation:** UI rendering is scheduled using `withContext(Dispatchers.Main)` or `lifecycleScope.launch`.
- **Thread Safety:** In-memory tables (`NeighborTable`, `RouteTable`, `DuplicateSuppressionCache`) utilize `ConcurrentHashMap`, `CopyOnWriteArrayList`, and `AtomicBoolean` to ensure lock-free, race-condition-free concurrency.
