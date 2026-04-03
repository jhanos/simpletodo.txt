# AGENTS.md — Coding Agent Guidelines

This repository contains a single project:

- **`app/`** — Android module (Kotlin, todo.txt app). The Gradle root is at the repo root.

---

## Build Commands

All Gradle commands run from the **repo root** (`/home/jhanos/git/todotxt`).

**Required environment variables** (proxy + JDK — Java 21 must be used; Java 25 is the system default but is incompatible with Gradle 8.4):

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export JAVA_OPTS="-Dhttps.proxyHost=172.23.194.209 -Dhttps.proxyPort=43051 \
                  -Dhttp.proxyHost=172.23.194.209  -Dhttp.proxyPort=43051"
```

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (signed with debug keystore)
./gradlew assembleRelease

# Build and run all unit tests
./gradlew test

# Clean
./gradlew clean
```

Output APK: `app/build/outputs/apk/release/app-release.apk`

---

## Test Commands

JVM unit tests — no emulator required.

```bash
./gradlew test
```

Test files live in `app/src/test/java/io/github/todotxt/app/model/`:
- `TaskTest.kt` — Task parsing, completion, recurrence, date arithmetic
- `TodoListTest.kt` — CRUD, filtering, sorting/grouping, archive

Framework: JUnit 4 + JUnit 3 `TestCase` compatibility.

---

## Language & SDK Versions

| Component | Version |
|---|---|
| Kotlin | 1.9.22 |
| JDK (required) | 21 (`/usr/lib/jvm/java-21-openjdk-amd64`) |
| Gradle | 8.4 |
| AGP | 8.2.0 |
| `minSdk` | 26 |
| `targetSdk` / `compileSdk` | 34 |

- Zero external runtime dependencies; `testImplementation("junit:junit:4.13.2")` only
- No AndroidX (`android.useAndroidX=false`)

---

## Android SDK

**Location:** `/home/jhanos/android-sdk`

**`local.properties`** (repo root) must contain:
```
sdk.dir=/home/jhanos/android-sdk
```

### Installing the SDK from scratch

If the SDK directory is missing, reinstall it as follows:

```bash
# 1. Download cmdline-tools
curl --proxy http://172.23.194.209:43051 -L \
  "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
  -o /tmp/cmdline-tools.zip

# 2. Extract
mkdir -p /home/jhanos/android-sdk/cmdline-tools
unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extracted
mv /tmp/cmdline-tools-extracted/cmdline-tools \
   /home/jhanos/android-sdk/cmdline-tools/latest

# 3. Install required SDK components
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
yes | /home/jhanos/android-sdk/cmdline-tools/latest/bin/sdkmanager \
  --sdk_root=/home/jhanos/android-sdk \
  --proxy=http --proxy_host=172.23.194.209 --proxy_port=43051 \
  "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

Installed components:
- `platforms;android-34`
- `build-tools;34.0.0`
- `platform-tools`

---

## Debug Keystore

**Location:** `~/.android/debug.keystore`  
Alias: `androiddebugkey` | Store/key password: `android`

The release build type in `app/build.gradle.kts` is configured to sign with this keystore.

### Regenerating the keystore if missing

```bash
mkdir -p ~/.android
keytool -genkeypair \
  -keystore ~/.android/debug.keystore \
  -alias androiddebugkey \
  -keypass android -storepass android \
  -dname "CN=Android Debug,O=Android,C=US" \
  -keyalg RSA -keysize 2048 -validity 10000
```

---

## Code Style — Kotlin

### Naming Conventions

| Construct | Convention | Example |
|---|---|---|
| Classes, interfaces, objects | `PascalCase` | `TodoList`, `FileStorage`, `Priority` |
| Data classes | `PascalCase` | `TaskItem`, `HeaderItem` |
| Enum classes | `PascalCase`; values in `UPPER_SNAKE_CASE` | `Priority.NONE`, `SortField.DUE_DATE` |
| Functions | `camelCase` | `markComplete()`, `filteredAndGrouped()` |
| Properties / variables | `camelCase` | `todoItems`, `dueDate` |
| Top-level `const val` | `UPPER_SNAKE_CASE` | `PREF_TODO_URI` |
| Companion object constants | `UPPER_SNAKE_CASE` | `EXTRA_TASK_TEXT`, `REQ_ADD_TASK` |
| Packages | lowercase reverse-domain | `io.github.todotxt.app.model` |

### Types and Null Safety

- Prefer non-nullable types; use `?` only when null is genuinely meaningful.
- Use `?.let { }`, `?: return`, `?.run { }` rather than explicit null checks.

### Error Handling

- Wrap SAF I/O in `try/catch(e: Exception)` and return a safe default (empty list, `false`, etc.).
- Show a `Toast` for user-visible errors; do not crash.

---

## Repository Notes

- The only build system is the Gradle Wrapper at the repo root.
- There are no `.cursor/rules/`, `.cursorrules`, or `.github/copilot-instructions.md` files in this repository.

