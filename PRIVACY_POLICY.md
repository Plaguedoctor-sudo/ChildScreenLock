# Privacy Policy for Child Screen Lock

**Effective Date:** October 4, 2026  
**Last Updated:** October 4, 2026  

Child Screen Lock ("we", "our", or "the App") is developed as an assistive parental control and digital wellbeing tool. We are deeply committed to protecting user privacy, especially the privacy of children and families.

This Privacy Policy explains how our application operates, how permissions are utilized, and our strict **zero-data-collection** architecture.

---

## 1. Summary: Purely Local & Air-Gapped Operation

- **No Remote Servers or Cloud Sync:** Child Screen Lock does not operate remote servers, tracking backends, or cloud storage.
- **Zero Internet Transmission:** The application requests **NO INTERNET PERMISSION** (`android.permission.INTERNET`). The app is physically incapable of transmitting any device data, personal information, or biometric representations over the internet.
- **Strictly Local Storage:** All configuration, whitelists, and cryptographic hashes reside exclusively in sandboxed application storage on your physical device.

---

## 2. Facial Biometrics & Camera Data (Children's Privacy)

To enable automatic recognition and access restriction when a child uses the device, the app utilizes on-device face detection:
- **On-Device Processing Only:** Camera frames are analyzed locally in real-time using embedded Google ML Kit vision libraries.
- **No Image Storage:** We **NEVER** save, store, or transmit raw photographs, camera recordings, or video frames of your child.
- **Hardware-Encrypted Numerical Embeddings:** Only a 128-dimensional numerical vector representing facial geometry is extracted. This vector is encrypted locally using the hardware-backed **Android KeyStore (AES-256-GCM)**.
- **No Third-Party Sharing:** Biometric data is never shared with third parties, advertisers, or external vendors.
- **Immediate Zeroization:** Parents can delete and permanently zeroize all biometric data at any time from the in-app Settings menu.

---

## 3. Use of Android Accessibility Service API

Child Screen Lock uses the Android `AccessibilityService` API strictly to deliver parental control and app-locking functionality:
- **Purpose:** To detect which application package is currently opened in the foreground. If the detected package is not on the parent's approved whitelist during an active lock session, the service navigates the device back to the home screen or displays an authorized lock notification.
- **Privacy Assurance:** 
  - The service **does not read, inspect, collect, or store** typed text, user inputs, passwords, messages, screen contents, or web browsing history.
  - The service is purely reactive to foreground window state transitions (`TYPE_WINDOW_STATE_CHANGED`).
  - Accessibility data is processed in volatile memory and never persisted or exported.

---

## 4. Use of Android VpnService (Anti-Telemetry & DNS Sinkhole)

Child Screen Lock utilizes the Android `VpnService` API to protect device privacy during active screen time sessions:
- **Local Loopback Sinkhole:** The VPN runs entirely on-device (`127.0.0.1`) without routing device traffic to external proxy servers or remote VPN endpoints.
- **Functionality:** It intercepts local DNS requests on UDP port 53. If a request is directed to known ad trackers, analytical beacons, or telemetry endpoints, the query is dropped locally (sinkholed to `0.0.0.0`). All other legitimate network requests are left unaltered.
- **No Traffic Logging:** The local VPN service does not inspect payload contents, decrypt SSL/TLS traffic, or maintain persistent browsing logs.

---

## 5. Security & Cryptographic Safeguards

In accordance with high-assurance security standards:
- **Parent PIN Protection:** PINs are never stored in plaintext. They are protected using memory-hard key derivation functions (`scrypt`/PBKDF2) with random cryptographic salt.
- **Anti-Brute Force Rate Limiting:** Progressive time delays are enforced after failed PIN attempts to prevent PIN spray and brute-force attacks.
- **Master Recovery Key:** An encrypted emergency recovery key (`RCV-XXXX-XXXX`) is generated locally to allow parents to reset credentials or safely uninstall without data leaks.

---

## 6. Children's Online Privacy Protection Act (COPPA) & GDPR Compliance

- We do not knowingly collect personal identifiable information from children under 13 (or under 16 in the EEA/UK).
- Because the app operates entirely offline without network permissions, no personal data from minors is ever gathered, processed externally, or monetized.

---

## 7. Data Retention & Deletion

- All application data (encrypted profiles, PIN hashes, and whitelist preferences) remains on the physical device until the parent either taps **"Reset All Profiles"** in Settings or uninstalls the app via standard Android App Management.
- Uninstalling the application completely purges all sandboxed data from the device.

---

## 8. Developer Contact & Inquiries

If you have questions regarding this Privacy Policy or the security architecture of Child Screen Lock, please contact the developer via the official Google Play developer contact address provided on the store listing.
