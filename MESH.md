# NEXORA — Mesh Protocol & Routing Reference

The full technical specification for the decentralized ad-hoc P2P mesh network is located at:
👉 **[docs/mesh.md](docs/mesh.md)**

### Key Highlights:
- **Hybrid Transports:** Google Nearby Connections (`P2P_CLUSTER`) + Wi-Fi Direct with raw TCP sockets on port `8888`.
- **Multi-Hop Relay:** Out-of-range packet delivery through intermediate nodes (maximum 3 hops).
- **Dynamic Topology Sync:** Reactive presence beacons (`TOPOLOGY_SYNC`) with a 35-second TTL route table expiration.
- **Delay-Tolerant Networking (DTN):** Store-and-forward custody (`AlertCustodyStore`) for emergency SOS broadcast alerts across network partitions.
- **Loop Suppression:** 1,000-entry bounded LRU `DuplicateSuppressionCache` to prevent re-broadcast loops.
