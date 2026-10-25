# Complete Deployment Guide
**Project:** Run for Victory by Ravi Kumar  
**Application ID:** `com.runforvictory.tuition`  
**Target Platform:** Android (API 26+) & Supabase PostgreSQL 15+  
**Target Release:** v1.0.0 Production  

---

## 1. Architectural Overview & Environment Separation

Run for Victory maintains complete isolation between **Development** and **Production** environments:

| Component | Development Environment | Production Environment |
| :--- | :--- | :--- |
| **Application ID** | `com.runforvictory.tuition.debug` | `com.runforvictory.tuition` |
| **Backend Host** | `http://localhost:54321` (Local Supabase) | `https://<prod-ref>.supabase.co` |
| **Database** | PostgreSQL 15 Local Docker Container | Supabase AWS AP-South-1 (Mumbai) |
| **Firebase Project** | `run-for-victory-dev` | `run-for-victory-prod` |
| **Code Minification** | Disabled (`isMinifyEnabled = false`) | Enabled (R8 full shrinking & Proguard) |
| **Cleartext HTTP** | Disabled | Blocked via `network_security_config.xml` |
| **Logging** | Console logging enabled | Stripped via Proguard `-assumenosideeffects` |

---

## 2. Backend Deployment (Supabase)

### Step 2.1: Project Initialization & Configuration
1. Log in to the [Supabase Dashboard](https://supabase.com/dashboard) and create a new project:
   - **Name:** `run-for-victory-production`
   - **Region:** `ap-south-1` (Mumbai, India) to minimize network latency to Hyderabad.
   - **Database Password:** Generate a high-entropy 32-character password and store it in an enterprise password vault (e.g. 1Password, Bitwarden).
2. Configure authentication and deep link redirects matching [`supabase/config.toml`](file:///C:/Users/Nikitha%20kotha/.gemini/antigravity/scratch/run_for_victory/supabase/config.toml):
   - **Site URL:** `https://runforvictory.com`
   - **Redirect URLs:**
     - `runforvictory://auth/callback`
     - `runforvictory://auth/reset-password`
     - `https://runforvictory.com/auth/callback`
   - **Enable Signup:** Set to **OFF** (Strict invitation-only provisioning model).

### Step 2.2: Apply Version-Controlled Database Migrations
Deploy the 10 version-controlled migrations in exact topological order:
```bash
# Authenticate CLI
supabase login

# Link production project
supabase link --project-ref <your-production-project-ref>

# Push all migrations
supabase db push
```

Alternatively, apply using `psql` directly:
```bash
for file in supabase/migrations/*.sql; do
    echo "Applying $file..."
    psql "$DATABASE_URL" -f "$file"
done
```

Verify that all 66 migration and RLS assertions pass against the database:
```bash
node supabase/tests/verify_migrations.js
node supabase/tests/verify_security_rules.js
```

### Step 2.3: Production Edge Functions Deployment
Deploy the two production Edge Functions located in `supabase/functions/`:
```bash
# 1. Deploy FCM push notification dispatcher
supabase functions deploy send-push-notification --no-verify-jwt=false

# 2. Deploy scheduled maintenance and token eviction worker
supabase functions deploy cron-session-cleanup --no-verify-jwt=false
```

### Step 2.4: Production Secret Vaulting
Never store secrets in source code or client builds. Set secrets in Supabase Vault:
```bash
supabase secrets set \
  FIREBASE_SERVICE_ACCOUNT_KEY='{"type":"service_account",...}' \
  SUPABASE_SERVICE_ROLE_KEY='<vaulted-service-role-key>' \
  APP_SIGNING_SHA256='<release-keystore-sha256>'
```
Automated script provided at [`supabase/scripts/setup_secrets.ps1`](file:///C:/Users/Nikitha%20kotha/.gemini/antigravity/scratch/run_for_victory/supabase/scripts/setup_secrets.ps1).

---

## 3. Firebase Cloud Messaging (FCM) Deployment

1. In the [Firebase Console](https://console.firebase.google.com/), select the matching project `run-for-victory-prod`.
2. Register an Android Application:
   - **Package Name:** `com.runforvictory.tuition` (must match production `applicationId`).
   - **App Nickname:** `Run for Victory Production`
   - **SHA-256 Certificate Fingerprint:** Add the SHA-256 fingerprint from the release keystore:
     ```bash
     keytool -list -v -keystore keystore/release.keystore -alias run_for_victory
     ```
3. Download `google-services.json` and place it in the `app/` directory (gitignored).
4. Generate a Firebase Admin Service Account Private Key:
   - Navigate to **Project Settings** $\rightarrow$ **Service Accounts** $\rightarrow$ **Generate New Private Key**.
   - Vault the JSON key in Supabase as `FIREBASE_SERVICE_ACCOUNT_KEY` for Edge Function push dispatching.

---

## 4. Android Release Build & Signing

### Step 4.1: Keystore Generation & Security
If generating a new production signing key:
```bash
keytool -genkeypair -v \
  -keystore keystore/release.keystore \
  -alias run_for_victory \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass <SecurePassword> \
  -keypass <SecurePassword> \
  -dname "CN=Ravi Kumar, OU=Run for Victory, O=Run for Victory Tuition, L=Hyderabad, ST=Telangana, C=IN"
```

> [!CAUTION]
> **Signing Key Backup & Storage Rule:**
> Store `release.keystore` in an offline hardware encrypted drive or enterprise KMS (e.g., Google Cloud Secret Manager / AWS Secrets Manager). Never commit `.keystore` or `.jks` files to GitHub. Loss of this key prevents updating the app on Google Play!

### Step 4.2: Build & Sign Release APK
```powershell
# 1. Compile Release APK with R8 shrinking
cmd /c "set JAVA_HOME=C:\Users\Nikitha kotha\.tools\jdk-17\jdk-17.0.20.1+1&& set ANDROID_HOME=C:\Users\Nikitha kotha\Android\Sdk&& gradlew.bat assembleRelease"

# 2. Sign with apksigner (APK Signature Schemes v2 & v3)
$env:JAVA_HOME = "C:\Users\Nikitha kotha\.tools\jdk-17\jdk-17.0.20.1+1"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
& "C:\Users\Nikitha kotha\Android\Sdk\build-tools\34.0.0\apksigner.bat" sign `
  --ks "keystore/release.keystore" `
  --ks-key-alias run_for_victory `
  --ks-pass pass:<SecurePassword> `
  --key-pass pass:<SecurePassword> `
  --out "app/build/outputs/apk/release/app-release-signed.apk" `
  "app/build/outputs/apk/release/app-release-unsigned.apk"

# 3. Verify signature
& "C:\Users\Nikitha kotha\Android\Sdk\build-tools\34.0.0\apksigner.bat" verify --verbose "app/build/outputs/apk/release/app-release-signed.apk"
```

### Step 4.3: Build & Sign Android App Bundle (AAB for Google Play)
```powershell
# 1. Build AAB
cmd /c "set JAVA_HOME=C:\Users\Nikitha kotha\.tools\jdk-17\jdk-17.0.20.1+1&& set ANDROID_HOME=C:\Users\Nikitha kotha\Android\Sdk&& gradlew.bat bundleRelease"

# 2. Sign AAB with jarsigner
& "C:\Users\Nikitha kotha\.tools\jdk-17\jdk-17.0.20.1+1\bin\jarsigner.exe" `
  -keystore "keystore/release.keystore" `
  -storepass <SecurePassword> `
  -keypass <SecurePassword> `
  "app/build/outputs/bundle/release/app-release.aab" run_for_victory
```

---

## 5. Physical Phone Installation & Verification

When a physical Android phone is connected via USB with Developer Options and USB Debugging enabled:
```bash
# Check device connection
adb devices

# Install signed release APK
adb install -r app/build/outputs/apk/release/app-release-signed.apk

# Verify app launches
adb shell am start -n com.runforvictory.tuition/.app.MainActivity
```

---

## 6. Post-Deployment Smoke Test Protocol

1. **Owner Setup:** Sign in as `owner@runforvictory.com` and verify centre profile: Run for Victory by Ravi Kumar.
2. **Invitation Flow:** Issue an invite for a tutor and parent; claim using mobile link.
3. **Geofenced Clock-In:** At physical centre coordinates (`17.385044, 78.486671`), verify tutor clock-in records as `VERIFIED`.
4. **Push Delivery:** Create a daily work report; confirm instant lock-screen privacy push on the parent's device.
5. **Language Switch:** Toggle to Telugu (తెలుగు) in settings; restart app and confirm persistence.
