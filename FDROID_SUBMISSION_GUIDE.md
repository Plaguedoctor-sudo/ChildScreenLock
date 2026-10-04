# F-Droid Publication Guide for Child Screen Lock

This guide walks you through submitting **Child Screen Lock** to **F-Droid** completely free of charge.

---

## Why Child Screen Lock is Ideal for F-Droid
F-Droid is the official Free and Open Source (FOSS) repository for Android. Child Screen Lock meets all of F-Droid's strictest ethical criteria:
- **Zero Internet Permissions** (Air-gapped)
- **Zero Telemetry / Zero Tracking**
- **No Ads / No In-App Purchases**
- **Apache 2.0 Open Source License**

---

## Path 1: IzzyOnDroid Repository (Fastest & Easiest — Recommended)
**IzzyOnDroid** is the largest and most popular F-Droid repository extension. Apps in IzzyOnDroid are directly searchable and installable from the official F-Droid app client.

### Step 1: Push Project to GitHub
1. Go to [github.com/new](https://github.com/new) and create a new **Public** repository (e.g. `ChildScreenLock`).
2. Push your project to GitHub using the terminal:
   ```bash
   cd /home/nitin/openai-project/ChildScreenLock
   git init
   git add .
   git commit -m "Initial release v1.0.0"
   git branch -M main
   git remote add origin https://github.com/<YOUR_GITHUB_USERNAME>/ChildScreenLock.git
   git push -u origin main
   ```

### Step 2: Create a GitHub Release
1. In your GitHub repository, click on **Releases** > **Draft a new release**.
2. **Tag version**: `v1.0.0`
3. **Release title**: `v1.0.0 - Initial Sovereign Release`
4. Attach the signed release APK: **`store_assets/bundle/app-release.apk`**.
5. Click **Publish release**.

### Step 3: Submit App to IzzyOnDroid
1. Go to the [IzzyOnDroid Inclusion Request Tracker](https://gitlab.com/IzzyOnDroid/repo/-/issues/new).
2. Choose the **App Inclusion Request** template.
3. Fill in the simple form:
   - **App Name**: `Child Screen Lock`
   - **Repository URL**: `https://github.com/<YOUR_GITHUB_USERNAME>/ChildScreenLock`
   - **Release URL**: `https://github.com/<YOUR_GITHUB_USERNAME>/ChildScreenLock/releases`
   - **License**: `Apache-2.0`
4. Submit the issue!
   *Izzy reviews requests within 24–48 hours. Once approved, your app will automatically appear in F-Droid for all users!*

---

## Path 2: Official F-Droid Main Repository (`fdroiddata`)
If you want to be listed in F-Droid's core repository built from source by F-Droid's build servers:

1. Fork the official F-Droid metadata repository on GitLab: [gitlab.com/fdroid/fdroiddata](https://gitlab.com/fdroid/fdroiddata).
2. Create a new file in `metadata/com.childlock.screentime.yml` using the template in `fdroid_metadata.yml`.
3. Update the `SourceCode` and `IssueTracker` URLs to match your public GitHub repository.
4. Open a Merge Request (MR) against `fdroid/fdroiddata`.
5. F-Droid's automated build bot tests the compilation and merges the request.
