# NEXORA (PeerConnect) — Technical Architecture & Workflow Specification

> **Package:** `com.fury.peerconnect`  
> **Application Label:** `NEXORA`  
> **Target Platform:** Android (Target SDK: 36, Min SDK: 24)  
> **Network Mode:** Decentralized Off-Grid Multi-Hop Mesh Network

---

## 📖 Documentation Hub

Comprehensive engineering guides and technical specifications are organized in the [`docs/`](docs/) directory:

| Specification | Path | Description |
|---|---|---|
| 🌐 **Mesh Protocol** | [`docs/mesh.md`](docs/mesh.md) | Multi-hop routing, hop limits, packet structure, TTL, and hybrid transports. |
| 🏛️ **System Architecture** | [`docs/arch.md`](docs/arch.md) | C4 architecture diagrams, Room schemas, AES-256 encryption, and layers. |
| 🛠️ **Engineering Workflow** | [`docs/workflow.md`](docs/workflow.md) | Gradle commands, APK generation, keystore signing, and ADB logcat telemetry. |
| 🤝 **Contribution Guide** | [`docs/contribute.md`](docs/contribute.md) | Development setup, coding guidelines, branch strategy, and PR submission. |
| 🔌 **Wire Compatibility** | [`docs/wire_compat.md`](docs/wire_compat.md) | Low-level payload wire framing and serialization specifications. |

---

## 1. Executive Summary

**NEXORA** is a decentralized, off-grid peer-to-peer (P2P) mesh communication and emergency response Android application. It functions entirely without cellular network connectivity, Wi-Fi routers, or central cloud infrastructure.

Using a **Hybrid Dual-Transport Engine** combining **Google Nearby Connections (P2P Cluster)** and **Native Wi-Fi Direct (Wi-Fi P2P) with TCP socket multiplexing**, NEXORA forms self-healing ad-hoc mesh clusters. Devices discover each other locally, synchronize routing tables via multi-hop topology broadcasts, relay messages across intermediary nodes (up to a 3-hop ceiling), and utilize Delay-Tolerant Networking (DTN) store-and-forward custody for offline delivery.

---

## 2. Technology Stack & Core Components

| Layer | Technologies & Libraries | Key Components |
|---|---|---|
| **UI & Presentation** | Material Design 3, AndroidX, ViewBinding | `MainActivity`, `PeerAdapter`, `ChatAdapter`, `AlertAdapter`, `RecentActivityAdapter`, `ConnectedPeerAdapter` |
| **P2P Transport** | Google Nearby Connections API (`P2P_CLUSTER`), Android Wi-Fi Direct (`WifiP2pManager`) | `NearbyMeshTransport`, `WifiP2pMeshTransport`, `WifiP2pMeshManager`, `TcpTransportManager` (Port `8888`) |
| **Mesh Routing & DTN** | Ad-hoc multi-hop routing engine, Custody store | `RoutingEngine`, `RouteTable`, `RouteEntry`, `DuplicateSuppressionCache`, `AlertCustodyStore` |
| **Security & Encryption** | Java Cryptography Extension (JCE), AES-256 | `SecurityHelper` (`AES/CBC/PKCS5Padding`, 256-bit symmetric key) |
| **Local Persistence** | AndroidX Room ORM, SQLite, SharedPreferences | `AppDatabase`, `PeerDao`, `MessageDao`, `AlertDao`, `UserManager` (`PeerConnectPrefs`) |
| **File & Attachment I/O**| Android Storage Access Framework (SAF), AndroidX FileProvider | `FileStorageManager`, `androidx.core.content.FileProvider` (`com.fury.peerconnect.fileprovider`) |
| **Asynchronous Engine** | Kotlin Coroutines, Java Concurrency (`ExecutorService`, `ConcurrentHashMap`, `AtomicBoolean`) | `Dispatchers.IO`, `LifecycleCoroutineScope` |

---

## 3. High-Level System Architecture

```mermaid
flowchart TB
    subgraph UI_Layer ["Presentation Layer (UI)"]
        MA["MainActivity"]
        DASH["Dashboard Tab\n(Node Metrics, Recent Activity)"]
        PEERS["Peers / Messages Tab\n(Peer List, Pairing Mode)"]
        ALERTS["Alerts Tab\n(SOS, Broadcast Feed, Filter Chips)"]
        CHAT["Direct Chat / Thread View\n(1-on-1 Chat, File Attachments)"]
        MA --> DASH
        MA --> PEERS
        MA --> ALERTS
        MA --> CHAT
    end

    subgraph Logic_Layer ["Application Logic & Security"]
        UM["UserManager\n(Node Identity & Nickname)"]
        SEC["SecurityHelper\n(AES-256 Encryption / Decryption)"]
        FSM["FileStorageManager\n(Payload Serialization & File I/O)"]
    end

    subgraph Routing_Layer ["Mesh Routing & DTN Engine"]
        RE["RoutingEngine\n(Packet Router & Dispatcher)"]
        RT["RouteTable\n(Next-Hop Cache & TTL Tracker)"]
        DSC["DuplicateSuppressionCache\n(Seen Message Deduplication)"]
        ACS["AlertCustodyStore\n(Delay-Tolerant Store-and-Forward)"]
        RE --> RT
        RE --> DSC
        RE --> ACS
    end

    subgraph Transport_Layer ["Hybrid Transport Abstraction"]
        CM["ConnectionManager\n(Active Socket & Session Registry)"]
        MT["<<Interface>> MeshTransport"]
        NMT["NearbyMeshTransport\n(Google Nearby P2P_CLUSTER)"]
        WMT["WifiP2pMeshTransport\n(Wi-Fi Direct Wrapper)"]
        TCP["TcpTransportManager\n(ServerSocket :8888 / TCP Sockets)"]
        WPM["WifiP2pMeshManager\n(Group Owner & Client Manager)"]
        
        MT <|.. NMT
        MT <|.. WMT
        WMT --> CM
        WMT --> TCP
        WMT --> WPM
    end

    subgraph Data_Layer ["Local Storage & Offline Database"]
        DB[("Room Database\nAppDatabase")]
        P_DAO["PeerDao\n(PeerEntity)"]
        M_DAO["MessageDao\n(MessageEntity)"]
        A_DAO["AlertDao\n(AlertEntity)"]
        DB --> P_DAO
        DB --> M_DAO
        DB --> A_DAO
    end

    MA --> RE
    MA --> UM
    MA --> SEC
    MA --> FSM
    MA --> DB
    RE --> MT
    RE --> CM
```

---

## 4. Detailed Data & Class Model

```mermaid
classDiagram
    class MeshMessage {
        +String messageId
        +String sourcePeerId
        +String destinationPeerId
        +MessageType type
        +byte[] payload
        +long timestamp
        +int hopCount
        +copy()
    }

    class MessageType {
        <<enumeration>>
        DIRECT
        BROADCAST_ALERT
        ACK
        TOPOLOGY_SYNC
    }

    class RouteEntry {
        +String destinationPeerId
        +String nextHopAddress
        +String transportId
        +int hopCount
        +long lastUpdated
        +long expiresAt
        +isDirect() boolean
        +isExpired(now) boolean
    }

    class CustodyAlert {
        +MeshMessage message
        +String originPeerId
        +Set~String~ deliveredNeighbors
        +long createdAt
        +long expiresAt
    }

    class PeerEntity {
        +String name
        +String endpointId
        +long lastSeenTimestamp
        +boolean isOnline
        +String nextHop
        +int hopDistance
        +boolean isReachable
    }

    class MessageEntity {
        +int id
        +String messageId
        +String senderId
        +String recipientId
        +String content
        +long timestamp
        +boolean isSent
        +String deliveryStatus
        +String messageType
        +String localPath
        +String fileName
        +long fileSize
        +String alertId
    }

    class AlertEntity {
        +int id
        +String alertId
        +String originPeerId
        +String title
        +String description
        +long timestamp
        +long expiresAt
        +String attachmentPath
        +boolean isRead
        +boolean isCustodyActive
    }

    class RoutingEngine {
        -String myDeviceId
        -ConnectionManager connectionManager
        -RouteTable routeTable
        -DuplicateSuppressionCache duplicateCache
        -AlertCustodyStore alertCustodyStore
        +registerTransport(MeshTransport)
        +sendDirectMessage(dest, payload, msgId)
        +broadcastAlert(payload, alertId)
        +broadcastPresence(presence, transportId)
        +processIncomingMessage(msg, sender, transportId)
        +sendAck(target, originalMsgId)
        +flushPendingUnicast(destinationPeerId)
    }

    MeshMessage --> MessageType
    RoutingEngine --> MeshMessage
    RoutingEngine --> RouteEntry
    RoutingEngine --> CustodyAlert
    AlertEntity ..> CustodyAlert
    MessageEntity ..> MeshMessage
    PeerEntity ..> RouteEntry
```

---

## 5. Core Workflows & Protocols

### 5.1. Dual-Transport Peer Discovery & Topology Synchronization

NEXORA operates an automated local mesh handshake:

1. **Nearby Transport**: Discovers endpoints using the Service ID `com.fury.peerconnect_v2` under `P2P_CLUSTER` topology.
2. **Wi-Fi Direct Transport**: Establishes autonomous Group Owners (GO) and negotiates client connections. Group Owners start a multi-client `ServerSocket` on TCP port `8888`.
3. **Topology Broadcast**: Once connected, nodes exchange `TOPOLOGY_SYNC` messages containing `PresencePayload` (device ID, status, reachable neighbors, hop distance).

```mermaid
sequenceDiagram
    autonumber
    participant NodeA as Node A (Local)
    participant Transports as Transport Layer (Nearby / Wi-Fi P2P)
    participant NodeB as Node B (Direct Neighbor)
    participant NodeC as Node C (2 Hops away)

    Note over NodeA,NodeB: 1. Discovery & Connection Handshake
    NodeA->>Transports: startDiscovery() & startAdvertising()
    NodeB->>Transports: startDiscovery() & startAdvertising()
    Transports-->>NodeA: onEndpointConnected(NodeB, transportId)
    Transports-->>NodeB: onEndpointConnected(NodeA, transportId)

    Note over NodeA,NodeB: 2. Routing Table Initialization
    NodeA->>NodeA: RouteTable.updateRoute(dest=NodeB, nextHop=NodeB, hops=1)
    NodeB->>NodeB: RouteTable.updateRoute(dest=NodeA, nextHop=NodeA, hops=1)

    Note over NodeB,NodeA: 3. Topology Propagation
    NodeB->>NodeA: TOPOLOGY_SYNC [subject=NodeC, hops=1 from NodeB]
    NodeA->>NodeA: RouteTable.updateRoute(dest=NodeC, nextHop=NodeB, hops=2)
    NodeA->>NodeA: Flush pending custody messages for NodeB & NodeC
```

---

### 5.2. Multi-Hop Direct Messaging & End-to-End Acknowledgment

When Node A sends a private message to Node C (out of direct radio range, via Node B):

1. **Encryption**: `SecurityHelper` encrypts message payload using AES-256 (`AES/CBC/PKCS5Padding`).
2. **Route Resolution**: `RoutingEngine` queries `RouteTable` for `destinationPeerId = NodeC` $\rightarrow$ resolves `nextHop = NodeB`.
3. **Hop Control**: Message is wrapped into `MeshMessage(hopCount=0)`. Intermediary nodes drop packets if `hopCount > 3`.
4. **Deduplication**: `DuplicateSuppressionCache` inspects `messageId` at every hop.
5. **End-to-End ACK**: Destination Node C generates an `ACK` message addressed to Node A, routed back through the mesh.

```mermaid
sequenceDiagram
    autonumber
    participant NodeA as Node A (Sender)
    participant NodeB as Node B (Relay Node)
    participant NodeC as Node C (Recipient)

    Note over NodeA: User writes private message to Node C
    NodeA->>NodeA: SecurityHelper.encrypt(plaintext)
    NodeA->>NodeA: RouteTable.getRoute(dest=NodeC) -> returns nextHop=NodeB
    NodeA->>NodeA: DuplicateSuppressionCache.markProcessed(msgId)

    NodeA->>NodeB: MeshMessage(DIRECT, src=NodeA, dest=NodeC, hop=0, msgId=101)
    
    Note over NodeB: Node B processes packet
    NodeB->>NodeB: DuplicateSuppressionCache.isDuplicate(101) -> false
    NodeB->>NodeB: Check destination: NodeC != myDeviceId
    NodeB->>NodeB: Increment hopCount: 0 + 1 = 1 (hopCount <= 3)
    NodeB->>NodeB: RouteTable.getRoute(dest=NodeC) -> returns nextHop=NodeC
    NodeB->>NodeC: MeshMessage(DIRECT, src=NodeA, dest=NodeC, hop=1, msgId=101)

    Note over NodeC: Node C delivers to UI
    NodeC->>NodeC: Destination matched (myDeviceId == NodeC)
    NodeC->>NodeC: SecurityHelper.decrypt(cipherText)
    NodeC->>NodeC: Room DB -> MessageDao.insert(MessageEntity)
    NodeC->>NodeC: UI displays decrypted chat message

    Note over NodeC,NodeA: End-to-End Acknowledgment (ACK)
    NodeC->>NodeB: MeshMessage(ACK, src=NodeC, dest=NodeA, payload=ackMsgId=101)
    NodeB->>NodeA: MeshMessage(ACK, src=NodeC, dest=NodeA, payload=ackMsgId=101)
    NodeA->>NodeA: MessageDao.updateStatus(101, "DELIVERED")
    NodeA->>NodeA: UI updates message checkmark
```

---

### 5.3. Emergency SOS Broadcast & DTN Store-and-Forward (Custody)

In disaster or network partition scenarios where routes are absent:

1. **Controlled Flooding**: Emergency SOS alerts (`BROADCAST_ALERT`) are broadcast across all active transports (`Nearby` + `Wi-Fi Direct`).
2. **Custody Storage**: The alert is stored locally in `AlertCustodyStore` with a validity Time-To-Live (TTL).
3. **Opportunistic Forwarding**: When an isolated device comes into contact with another peer later, `flushPendingUnicast()` and `getActiveAlertsForNeighbor()` immediately replicate the emergency alert to the newly discovered peer.

```mermaid
sequenceDiagram
    autonumber
    participant SOS_Node as Origin Node (Emergency)
    participant Mesh as Neighbor Cluster
    participant New_Peer as Newly Arrived Peer (Later)

    Note over SOS_Node: User presses SOS button
    SOS_Node->>SOS_Node: Generate AlertEntity & MeshMessage(BROADCAST_ALERT)
    SOS_Node->>SOS_Node: AlertCustodyStore.storeAlert(alertMsg, TTL)
    SOS_Node->>Mesh: Flood broadcastAcrossTransports(alertMsg)
    Mesh->>Mesh: DuplicateSuppressionCache deduplication & display SOS

    Note over SOS_Node,New_Peer: Hours later / Node enters radio range
    New_Peer->>SOS_Node: Transport connection established
    SOS_Node->>SOS_Node: AlertCustodyStore.getActiveAlertsForNeighbor(New_Peer)
    SOS_Node->>New_Peer: Replicate buffered alert(s)
    New_Peer->>New_Peer: AlertCustodyStore.markDeliveredTo(New_Peer)
    New_Peer->>New_Peer: Display Emergency Alert Banner & Sound Alert
```

---

## 6. Node Lifecycle & Radio State Machine

```mermaid
stateDiagram-v2
    [*] --> OFFLINE

    OFFLINE --> INITIALIZING: Launch App / Permissions Granted
    INITIALIZING --> AUTO_DISCOVERING: Start Default P2P Scan
    
    state AUTO_DISCOVERING {
        [*] --> SCANNING
        SCANNING --> ADVERTISING: Role Switch Timer (Auto Alternate)
        ADVERTISING --> SCANNING: Discovery Refresh Timer
    }

    AUTO_DISCOVERING --> CONNECTING: Peer Found (Nearby / Wi-Fi Direct)
    CONNECTING --> CONNECTED_CLIENT: Connected to Group Owner
    CONNECTING --> CONNECTED_HOST: Created Group / Host Mode

    state CONNECTED {
        CONNECTED_CLIENT --> MESH_ACTIVE: TCP Socket Handshake (:8888)
        CONNECTED_HOST --> MESH_ACTIVE: ServerSocket Accepting Clients
        
        state MESH_ACTIVE {
            [*] --> TOPOLOGY_EXCHANGE
            TOPOLOGY_EXCHANGE --> IDLE_ROUTING
            IDLE_ROUTING --> RELAYING_PACKET: Inbound Transit Packet
            RELAYING_PACKET --> IDLE_ROUTING: Forward Complete
            IDLE_ROUTING --> CUSTODY_BUFFERING: Route Unreachable (DTN)
            CUSTODY_BUFFERING --> IDLE_ROUTING: Neighbor Reconnected
        }
    }

    CONNECTED_CLIENT --> CONNECTED
    CONNECTED_HOST --> CONNECTED

    CONNECTED --> AUTO_DISCOVERING: Connection Lost / Radio Reset
    CONNECTED --> OFFLINE: App Terminated / Bluetooth & Wi-Fi Off
```

---

## 7. Security Architecture & Observations

- **Encryption Standard**: AES-256 symmetric cipher in CBC mode with PKCS5 padding (`AES/CBC/PKCS5Padding`).
- **Cryptographic Keying**: Embedded static 256-bit pre-shared key (`12345678901234567890123456789012`) with a zero-byte 16-byte initialization vector (IV).
- **Authorization Callback**: `RoutingEngine` includes an `isPeerAuthorized` predicate hook prior to routing application-level payloads.
- **Loop Avoidance**: Deduplication filter prevents infinite packet circulation in dense mesh loops.
- **Hop Ceiling**: Hard ceiling limit of 3 hops protects battery life and wireless channel capacity during broadcast flooding.
