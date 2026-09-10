# NEXORA — Engineering & Build Workflow Reference

The complete engineering and operational workflow documentation is located at:
👉 **[docs/workflow.md](docs/workflow.md)**

### Key Highlights:
- **Build Tasks:**
  - `./gradlew assembleDebug`: Generate debug APK.
  - `./gradlew assembleRelease`: Generate signed production APK.
  - `./gradlew test`: Run the JUnit and coroutine unit test suite.
- **Keystore & Security:** Debug signing runs automatically; release signing uses `release.keystore` via environment variables.
- **Multi-Device ADB Telemetry:** Filter logs via `adb logcat -s "RoutingEngine:D" "NearbyMeshTransport:D" "WifiP2pMeshTransport:D" "MainActivity:D"`.
- **Mesh Test Matrix:** Guidelines for testing point-to-point links, 3-hop linear relays, and partition recovery.
