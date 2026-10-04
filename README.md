# Child Screen Lock (Parental App Whitelist & Face Recognition)

An Android application built with Kotlin, Material 3, CameraX, Google ML Kit, and an App Blocker Engine to enforce child screen time limits and auto-lock unapproved apps.

---

## 🚀 Features

1. **Parent App Whitelist**:
   * Parents can choose exactly which apps the child is permitted to use (e.g. Educational apps, Phone, Calculator, Study tools).
   * Any unapproved app (games, YouTube, social media) is instantly blocked when opened.

2. **Continuous Lock Session Timer**:
   * Continuous countdown timer (15 mins, 30 mins, 1 hour, 2 hours, etc.) managed by a Foreground Service.
   * Lock mode runs uninterrupted for the selected duration with a live persistent notification.

3. **Child Face Recognition Guard**:
   * **Face Enrollment**: Front-camera face enrollment powered by **Google ML Kit Face Detection**.
   * **Embeddings & Similarity**: Generates face embedding vectors and matches using Cosine Similarity.
   * **Auto-Lock**: When enabled, detecting the child engages or reinforces the child lock session.

4. **Fullscreen Lock Barrier**:
   * When an unapproved app is opened, `LockOverlayActivity` takes over the entire screen.
   * Back button is blocked to prevent bypassing.
   * Child can only tap "Go To Allowed Apps" (home) or have a parent enter the PIN.

5. **Parent PIN Protection**:
   * Default PIN: `1234` (hashed using SHA-256).
   * Required to stop a lock session, change whitelist settings, or bypass the lock overlay.

---

## 📁 Architecture & File Structure

```
ChildScreenLock/
├── app/src/main/
│   ├── AndroidManifest.xml                  // Permissions, activities, and accessibility service
│   ├── java/com/childlock/screentime/
│   │   ├── data/
│   │   │   ├── AppInfo.kt                   // App model (package, label, icon, isAllowed)
│   │   │   └── PreferencesManager.kt        // PIN hashing, whitelist, timer, & face embeddings
│   │   ├── face/
│   │   │   ├── FaceDetectorHelper.kt        // Google ML Kit Face Detection pipeline
│   │   │   └── FaceEmbeddingHelper.kt       // 128D embedding extraction & cosine similarity matching
│   │   ├── service/
│   │   │   ├── ChildLockAccessibilityService.kt // Intercepts unapproved apps & triggers overlay
│   │   │   ├── LockTimerService.kt          // Foreground service with live countdown timer
│   │   │   └── FaceMonitoringService.kt     // Continuous face recognition guard
│   │   ├── ui/
│   │   │   ├── MainActivity.kt              // Parent control panel / dashboard
│   │   │   ├── AppSelectionActivity.kt      // Allowed apps checklist with search
│   │   │   ├── AppAdapter.kt                // RecyclerView adapter for installed apps
│   │   │   ├── FaceEnrollmentActivity.kt    // CameraX preview & face enrollment UI
│   │   │   ├── LockOverlayActivity.kt       // Fullscreen blocking barrier
│   │   │   └── PinDialogHelper.kt           // PIN entry verification dialog
│   │   └── util/
│   │       └── PermissionHelper.kt          // Accessibility, Overlay, and Camera permission handlers
│   └── res/                                 // Material 3 layouts, vector icons, colors, and styles
```

---

## 🛠️ Required Device Permissions

To enable child screen time restrictions, the parent must grant 3 permissions from the in-app setup guide:
1. **Accessibility Service**:
   * Settings -> Accessibility -> Installed Apps -> **Child Lock App Blocker** -> Turn ON.
   * *Purpose*: Detects which app is opened in real time.
2. **Display Over Other Apps**:
   * Settings -> Apps -> Special app access -> Display over other apps -> Turn ON.
   * *Purpose*: Allows the app to display the lock barrier over blocked apps.
3. **Camera Permission**:
   * *Purpose*: Captures front camera frames for face enrollment and recognition.

---

## 📦 Ready-to-Install APK

The project has been compiled and the debug APK is located at:
`ChildScreenLock/app/build/outputs/apk/debug/app-debug.apk`

To install directly to a connected Android device or emulator via ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
