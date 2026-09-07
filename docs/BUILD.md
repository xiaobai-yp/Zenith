# ZenithThermal Native Build Guide

## Prerequisites

- Android NDK r25+ (set `ANDROID_NDK_HOME`)
- CMake 3.18+
- clang (provided by NDK)
- For Kotlin app: Android Studio, JDK 21, Gradle

## Building the Native Daemon

```bash
cd native/scripts
./build.sh /path/to/android-ndk Release
```

Or manually with CMake:

```bash
cd native
mkdir -p build/arm64-v8a
cd build/arm64-v8a

cmake ../.. \
    -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release

cmake --build . -j$(nproc)
```

### Output

- `zenithd` — daemon binary (install to `/system/bin/`)
- `libzenith.so` — shared library (optional, for JNI binding)

## Installing on Device

```bash
adb root
adb remount
adb push native/build/arm64-v8a/zenithd /system/bin/zenithd
adb push daemon/init.zenith.rc /system/etc/init/init.zenith.rc
adb push daemon/config/profiles.json /system/etc/zenith/profiles.json

# SELinux (if enforcing)
adb push daemon/sepolicy/zenithd.te /vendor/etc/selinux/
adb push daemon/sepolicy/file_contexts /vendor/etc/selinux/
adb push daemon/sepolicy/property_contexts /vendor/etc/selinux/
```

## Building the Kotlin App

```bash
cd /path/to/ZenithThermal
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### CI/CD

GitHub Actions workflow at `.github/workflows/build.yml` builds the debug APK
on push to `main`.

## String Encryption

Pre-build step to encrypt sensitive string literals:

```bash
cd native
python3 ../tools/enpack_strings.py daemon/*.c lib/*.h
```

## Signing

```bash
./tools/generate_keystore.sh keystore/zenith-release.jks
```

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for component overview.
See [DAEMON_API.md](DAEMON_API.md) for IPC protocol specification.
