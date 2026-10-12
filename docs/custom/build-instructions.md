# Mihon Custom - Build Instructions

## Prerequisites
- Android Studio Ladybug or later / JDK 21
- Android SDK (API 35 platform, build-tools 35.0.0, NDK if required)
- Git

## Environment Setup
Set the `ANDROID_HOME` environment variable or specify `sdk.dir` in `local.properties`:
```properties
sdk.dir=C:\\Users\\Administrator\\AppData\\Local\\Android\\Sdk
```

## Build Variants
The personal fork uses the unique application ID `app.mihon.custom` to ensure it never collides with or overwrites official Mihon:
- **Debug:** `app.mihon.custom.dev`
- **Release:** `app.mihon.custom`
- **FOSS:** `app.mihon.custom.foss`
- **Nightly:** `app.mihon.custom.debug`
- **Benchmark:** `app.mihon.custom.benchmark`

---

## 1. Building Debug APK
Run the Gradle wrapper:
```bash
./gradlew assembleDebug
```
The resulting APKs will be located under:
```
app/build/outputs/apk/debug/
  - app-universal-debug.apk
  - app-arm64-v8a-debug.apk
  - app-armeabi-v7a-debug.apk
  - app-x86_64-debug.apk
  - app-x86-debug.apk
```

---

## 2. Producing Release-Signed APK with Local Keystore
To produce a signed release APK without committing any private keys or passwords:

1. **Generate a keystore (if you do not already have one):**
   ```bash
   keytool -genkeypair -v -keystore release.keystore -alias mihon-custom -keyalg RSA -keysize 2048 -validity 10000
   ```

2. **Create `keystore.properties` in the project root (this file is ignored by Git):**
   ```properties
   storeFile=C:\\path\\to\\release.keystore
   storePassword=your_store_password
   keyAlias=mihon-custom
   keyPassword=your_key_password
   ```

3. **Build the release APK:**
   ```bash
   ./gradlew assembleRelease
   ```
   The signed APK will be output in:
   ```
   app/build/outputs/apk/release/
   ```
