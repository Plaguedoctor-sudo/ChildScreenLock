# Google Play Store Listing & Policy Declaration Guide

This document contains everything you need to copy and paste into the **Google Play Console** ([play.google.com/console](https://play.google.com/console)) to successfully publish **Child Screen Lock**.

---

## 1. Store Listing Details

### App Name (Max 30 characters)
```text
Child Screen Lock: Parent Safe
```

### Short Description (Max 80 characters)
```text
Air-gapped parental control with app whitelisting & offline face recognition.
```
*(76 characters)*

### Full Description (Max 4,000 characters)
```text
Child Screen Lock is an assistive parental control and screen time management tool designed for parents and legal guardians who demand uncompromising digital privacy and security.

Built with an air-gapped, zero-cloud architecture, Child Screen Lock requests NO INTERNET PERMISSION. Every profile, whitelist rule, and face embedding remains 100% on your physical device.

KEY FEATURES:

★ Strictly Approved App Whitelisting
Choose exactly which apps your child is permitted to open (educational tools, reading apps, or specific games). Any unapproved app is automatically restricted, immediately returning the device to the home screen.

★ Local Biometric Comparison (Front & Back Camera)
Register your child's face profile to detect when the child is interacting with the device. Using Google ML Kit on-device machine learning, raw camera feeds are never stored. Faces are converted to mathematical embeddings and encrypted with hardware-backed AES-256-GCM keys via Android KeyStore.

★ Expansive Lock Sessions
Enforce focused study and sleep intervals from 15 minutes, 1 hour, 24 hours, or custom extended durations up to 1 year with continuous system persistence.

★ Anti-Telemetry & Tracker Neutralization
When lock sessions are engaged, a strictly local loopback DNS sinkhole intercepts and nullifies background ad trackers and telemetry requests, preserving device performance and family privacy.

★ Tamper-Resistant Parent Security
• Memory-Hard Key Derivation (scrypt/PBKDF2) prevents PIN discovery.
• Progressive exponential rate limiting blocks brute-force attempts.
• Master Recovery Key (RCV-XXXX-XXXX) ensures parents can always recover access or safely uninstall even if their PIN is forgotten.
• Cryptographic tamper-evident local audit ledger.

★ Zero Cloud & Zero Tracking
• No internet permission required or requested.
• Zero data collection, analytics, or third-party SDKs.
• No subscription fees, no ad tracking, and no external servers.

IMPORTANT LEGAL & OPERATIONAL NOTICE:
Child Screen Lock is an assistive parental tool and is NOT a substitute for active, direct adult supervision. The application cannot prevent physical hardware manipulation (such as battery removal, physical device resets, or hardware power-offs). All features operate strictly on-device under the explicit consent and control of the authorized parent or device owner.
```

---

## 2. Policy Declaration: Accessibility Service API

Google Play strictly reviews all apps using `AccessibilityService`. When prompted in the Play Console, provide the following exact information:

### Question: Is the Accessibility API used for accessibility purposes (e.g. for people with disabilities) or alternative purposes?
- **Selection:** Alternative purpose (Parental Control / App Access Control)

### Written Justification for Google Play Review Team:
```text
Child Screen Lock uses the Android AccessibilityService API strictly as a parental control feature. The service observes TYPE_WINDOW_STATE_CHANGED events to detect when an unapproved application is launched in the foreground during an active parent-configured screen time lock session. When an unapproved package is detected, the service immediately invokes GLOBAL_ACTION_HOME to safeguard the child's screen time boundaries.

Privacy and Security Safeguards:
1. The service DOES NOT read, inspect, or store any text input, keystrokes, passwords, personal messages, or screen contents.
2. The service operates strictly offline (the app has zero internet permissions) and never transmits any device usage data.
3. The accessibility service requirement is disclosed to the parent in a prominent in-app disclosure dialog before requesting authorization.
```

---

## 3. Policy Declaration: VpnService API

Google Play requires a dedicated declaration form for any app utilizing `android.net.VpnService`.

### Question: Select the core purpose of the VpnService:
- **Selection:** Device Security / Ad & Tracker Filter

### Written Justification for Google Play Review Team:
```text
Child Screen Lock utilizes the Android VpnService to operate a strictly local on-device loopback DNS sinkhole (127.0.0.1) during active parental lock sessions. 

The service intercepts local DNS queries on UDP port 53 and resolves known telemetry, tracking, and advertising domains to 0.0.0.0, protecting child and family privacy while using whitelisted educational apps. 

Traffic Protection Details:
- The VPN operates entirely locally on the device and does NOT route, forward, or tunnel user traffic to any remote proxy or external server.
- The service does not decrypt TLS/SSL payloads or inspect private application data.
- The app requests zero internet permissions and cannot transmit user traffic.
```

---

## 4. Policy Declaration: Query All Packages (`QUERY_ALL_PACKAGES`)

Google Play requires a specific declaration when requesting `QUERY_ALL_PACKAGES`:

### Question: Why does your app need the QUERY_ALL_PACKAGES permission?
- **Selection:** Device Management / Parental Control (Whitelisting permitted applications)

### Written Justification for Google Play Review Team:
```text
Child Screen Lock is an assistive parental control application designed to restrict children to specific parent-approved applications. 

To provide this core functionality, the app must query installed applications on the device so that parents can view the list of installed educational/entertainment apps and select which ones are whitelisted. Without this permission, parents cannot choose allowed apps, rendering the parental restriction feature inoperable.
```

---

## 5. Google Play Data Safety Section Answers

When completing the mandatory **Data Safety** questionnaire:

1. **Does your app collect or share any of the required user data types?**
   - Select: **NO**
2. **Are all user data collected by your app encrypted in transit?**
   - Select: **Not applicable (no data is collected or transmitted)**
3. **Do you provide a way for users to request that their data is deleted?**
   - Select: **YES**
   - Explanation: *All configuration and encrypted biometric embeddings reside strictly on the user's physical device and can be permanently zeroized and deleted at any time via the in-app "Reset All Profiles" feature or by uninstalling the application.*

---

## 6. Target Audience and Content Rating

### Target Audience:
- Select: **18 and over (Parents & Guardians)**
- *Important Tip:* By classifying the primary audience as **Parents/Guardians** managing child devices (rather than marketing directly to children), your app falls under parental tools rather than the strict Families Policy restrictions that mandate third-party certified SDK compliance.

### Ads:
- **Does your app contain ads?**
  - Select: **NO**

### Financial Features:
- **Does your app contain financial features or in-app purchases?**
  - Select: **NO**

---

## 7. Privacy Policy URL Requirement

Google Play requires a publicly accessible HTTPS link to your Privacy Policy.
You can host the generated `privacy_policy.html` using:
- **GitHub Pages** (Free): Create a public repo named `child-screen-lock-privacy` and enable GitHub Pages on `privacy_policy.html`.
- **Google Sites** (Free): Copy and paste the text from `PRIVACY_POLICY.md` into a new Google Site.
- **Custom Domain**: Upload `privacy_policy.html` to your server.
