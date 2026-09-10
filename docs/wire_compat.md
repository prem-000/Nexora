# NEXORA Wire Protocol & Additive Compatibility Specification (WIRE_COMPAT)

> **Document Version:** 1.0.0  
> **Status:** Ratified Standard for Location, Tactical Feeds & Attachments  
> **Target Subsystems:** RoutingEngine, AlertCustodyStore, Chat Engine, Offline Maps Engine  

---

## 1. Principles of Additive Wire Compatibility

To prevent breaking deployed nodes, mesh packet relaying, or delay-tolerant custody stores, all location and messaging features in NEXORA adhere to strict non-negotiable rules:

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
  [ALERT]:<id>|<senderId>|<alertType>|<title>|<message>|<sentAt>|<attachmentPath>|<expiresAt>|<latitude>|<longitude>
  ```
* **Parser Rule:**
  Split by `|`. If `parts.size >= 10`, parse index 8 and 9 as Double latitude/longitude. If absent or invalid, treat alert as non-georeferenced.

---

### 2.4. Found Person Coordination (`[FOUND_PERSON]:`)
Specialized alert response protocol for search-and-rescue operations.

* **Envelope Type:** `MessageType.DIRECT` or `MessageType.BROADCAST_ALERT`
* **Wire Format (Decrypted):**
  ```text
  [FOUND_PERSON]:<alertId>|FOUND|<latitude>|<longitude>
  ```
* **Field Definitions:**
  | Field | Type | Description | Example |
  |---|---|---|---|
  | `alertId` | String | ID of the missing person alert | `alert_92b4` |
  | `status` | String | `FOUND` status indicator | `FOUND` |
  | `latitude` | Double | Rescuer's current latitude (or 0 if unshared) | `37.774929` |
  | `longitude` | Double | Rescuer's current longitude (or 0 if unshared) | `-122.419418` |
