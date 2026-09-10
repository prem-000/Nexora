# NeXoRa Wire Protocol & Additive Compatibility Specification (WIRE_COMPAT)

> **Document Version:** 1.0.0  
> **Status:** Ratified Standard for Location & Maps  
> **Target Subsystems:** RoutingEngine, AlertCustodyStore, Chat Engine, Offline Maps Engine

---

## 1. Principles of Additive Wire Compatibility

To prevent breaking deployed nodes, mesh packet relaying, or delay-tolerant custody stores, all location features in NeXoRa adhere to strict non-negotiable rules:

1. **Zero Changes to Transit Routing Logic:**  
   Mesh routers (`RoutingEngine`, `RouteTable`, `NearbyMeshTransport`, `WifiP2pMeshTransport`) operate exclusively on `MeshMessage` envelopes:
   ```kotlin
   data class MeshMessage(
       val messageId: String,
       val sourcePeerId: String,
       val destinationPeerId: String?,
       val type: MessageType,
       val payload: ByteArray,
       val timestamp: Long,
       val hopCount: Int
   )
   ```
   Intermediary relays only inspect `messageId` (deduplication) and `destinationPeerId` (next-hop routing). Relays treat `payload: ByteArray` as an opaque binary blob. Unmodified older nodes can relay new location frames without code updates.

2. **No Heavy Binaries on the Mesh:**  
   MBTiles, PMTiles, vector tile packages, map ZIPs, and satellite imagery are **NEVER** transmitted over the Bluetooth / Wi-Fi mesh. Only lightweight coordinate frames (~50–120 bytes) travel across the network.

3. **Additive String Prefixes for Decrypted Payloads:**  
   Decrypted message payloads use self-describing, forward-compatible prefixes (`[LOC]:`, `[LOC_LIVE]:`, `[ALERT]:`, `[FOUND_PERSON]:`). Unrecognized payloads fall back to safe text display without crashing older clients.

---

## 2. Location Frame Specifications

### 2.1. Direct Chat Location (`[LOC]:`)
Sent when a user shares their current location inside an individual 1-on-1 chat.

* **Envelope Type:** `MessageType.DIRECT`
* **Encryption:** AES-256 (`AES/CBC/PKCS5Padding`)
* **Wire Format (Decrypted):**
  ```text
  [LOC]:<latitude>|<longitude>|<timestamp>|<accuracy>|<optional_label>
  ```
* **Field Definitions:**
  | Field | Type | Description | Example |
  |---|---|---|---|
  | `latitude` | Double (6 decimals) | WGS-84 Latitude | `37.774929` |
  | `longitude` | Double (6 decimals) | WGS-84 Longitude | `-122.419418` |
  | `timestamp` | Long (millis) | GPS fix timestamp | `1725700000000` |
  | `accuracy` | Float (meters) | Horizontal GPS accuracy | `4.5` |
  | `label` | String (optional) | Human-readable place/note | `Current Location` |

* **Example Payload:**
  ```text
  [LOC]:37.774929|-122.419418|1725700000000|4.5|Current Location
  ```

---

### 2.2. Live Location Stream (`[LOC_LIVE]:`)
Periodic updates sent during an active temporary live-sharing session.

* **Envelope Type:** `MessageType.DIRECT`
* **Encryption:** AES-256
* **Cadence:** Every 15–30 seconds OR upon significant movement (> 15 meters). Never every second.
* **Wire Format (Decrypted):**
  ```text
  [LOC_LIVE]:<sessionId>|<latitude>|<longitude>|<timestamp>|<accuracy>|<status>|<durationSec>
  ```
* **Field Definitions:**
  | Field | Type | Description | Example |
  |---|---|---|---|
  | `sessionId` | String (UUID or hash) | Unique tracking session identifier | `sess_a81f09` |
  | `latitude` | Double | WGS-84 Latitude | `37.775102` |
  | `longitude` | Double | WGS-84 Longitude | `-122.419050` |
  | `timestamp` | Long | Timestamp of current fix | `1725700025000` |
  | `accuracy` | Float | Accuracy in meters | `3.8` |
  | `status` | String | `ACTIVE` or `STOPPED` | `ACTIVE` |
  | `durationSec`| Int | Total session duration in seconds (900, 1800, 3600) | `1800` |

* **Stop Signal:**
  ```text
  [LOC_LIVE]:sess_a81f09|0.0|0.0|1725700800000|0.0|STOPPED|0
  ```

---

### 2.3. Alert Location Extension (`[ALERT]:`)
Emergency SOS and broadcasts with optional geo-tags.

* **Envelope Type:** `MessageType.BROADCAST_ALERT`
* **Custody:** Stored and forwarded by `AlertCustodyStore`.
* **Legacy Wire Format:**
  ```text
  [ALERT]:<id>|<senderId>|<alertType>|<title>|<message>|<sentAt>|<attachmentPath>|<expiresAt>
  ```
* **Compatible Extended Wire Format:**
  ```text
  [ALERT]:<id>|<senderId>|<alertType>|<title>|<message>|<sentAt>|<attachmentPath>|<expiresAt>|<latitude>|<longitude>|<accuracy>
  ```
* **Compatibility Handling:**
  - Older NeXoRa clients inspect `parts.size >= 8` and read `parts.last()` or `parts[7]` as `expiresAt`. If 11 parts are present, older clients treat extra parts as tail metadata or safely default `expiresAt` without crash.
  - Newer clients inspect `parts.size >= 10`:
    - `parts[7]` $\rightarrow$ `expiresAt`
    - `parts[8]` $\rightarrow$ `latitude`
    - `parts[9]` $\rightarrow$ `longitude`
    - `parts.getOrNull(10)` $\rightarrow$ `accuracy` (optional)

---

### 2.4. Missing Person Alert & "I Found This Person" Flow

1. **Broadcast Alert:**
   - `alertType = "MISSING_PERSON"`
   - Contains person description, last known photo attachment, and last known coordinates via Extended Alert Format.
2. **Response Handshake (`[FOUND_PERSON]:`):**
   - Sent directly from finder to alert origin node via `MessageType.DIRECT`.
   ```text
   [FOUND_PERSON]:<alertId>|<finderPeerId>|<message>|<timestamp>|<canShareLocation:true|false>
   ```
3. **Permission Request & Temporary Live Sharing:**
   - Finder and Alert Sender establish a mutual temporary live session using `[LOC_LIVE]:` with explicit duration consent (15 min, 30 min, 1 hr, or Until Stopped).
   - Neither party is tracked automatically; mutual opt-in is strictly enforced.

---

## 3. Fallback & Network Partition Behavior

* **Mesh Reachability:**
  - If a direct neighbor or multi-hop path exists, live location updates arrive in real-time (~15–30s).
  - If a network partition occurs, the receiver UI maintains the last known point with a status indicator:
    `Last location • Last updated: 3 minutes ago`
  - As soon as topological reachability is re-established via `TOPOLOGY_SYNC`, live updates seamlessly resume.

---

## 4. Summary Table of Payload Prefixes

| Wire Prefix | Transport Envelope | Relayed by Legacy Nodes? | Purpose |
|---|---|---|---|
| `[LOC]:` | `DIRECT` | **Yes** (opaque payload) | Point-in-time chat location pin |
| `[LOC_LIVE]:` | `DIRECT` | **Yes** (opaque payload) | Throttled live-tracking coordinate stream |
| `[ALERT]:` | `BROADCAST_ALERT` | **Yes** (controlled flooding) | SOS & broadcasts with optional lat/lng |
| `[FOUND_PERSON]:` | `DIRECT` | **Yes** (opaque payload) | Response handshake for missing persons |
