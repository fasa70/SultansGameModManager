# Build Guide

## Release pipeline

Run `bash scripts/build-release.sh` from the repository root after configuring the JDK, Android SDK and NDK. The pipeline builds the native loader, validates its ELF/ABI/16 KB alignment contract, builds the Bootstrap AAR and unsigned loader split, stages the template and structural metadata transactionally, assembles the signed Manager APK, and verifies the final APK.

Native/template content is intentionally **not pinned by checked-in SHA-256**. Native changes therefore do not require updating `GameProfile`, release JSON, or test constants. The release metadata records only package, split, version and provider protocol. Certificate SHA-256 and ordinary APK/mod integrity summaries are separate mechanisms and remain in use.

## Loader template checks

The unsigned template must contain `AndroidManifest.xml`, `resources.arsc`, `classes.dex`, and `assets/modloader/arm64-v8a/modloader.bin`. It must be a readable ZIP with no duplicate entries or APK signature entries, and the native entry must be non-empty and `ZIP_STORED`. Package name, split name, version and provider contract must match the supported profile.

The Manager performs these structural checks before signing. It then signs the template with the device key and verifies v1/v2 signatures, payload preservation, certificate identity, and the final split set. The release verifier compares the Manager's embedded template bytes directly with the staged template; it does not compare a fixed native/template digest.

## Manual template build

```bash
cd android/manager
bash ./gradlew :bootstrap:assembleRelease \
  -PmanagerCertificateSha256=<64-lowercase-hex-characters> \
  -PmodloaderBinary=../../native/build-android-release/libmodloader.so

python ../bootstrap/build_split_template.py \
  --bootstrap-aar ../bootstrap/build/outputs/aar/bootstrap-release.aar \
  --bootstrap-manifest ../bootstrap/src/main/AndroidManifest.xml \
  --android-jar "$ANDROID_HOME/platforms/android-35/android.jar" \
  --aapt2 "$ANDROID_HOME/build-tools/<version>/aapt2.exe" \
  --d8 "$ANDROID_HOME/build-tools/<version>/d8.bat" \
  --output app/build/release-stage/modloader-template-10005.apk \
  --version-code 10005 --version-name 1.0.5

python ../bootstrap/build_split_template.py --verify \
  --output app/build/release-stage/modloader-template-10005.apk
```

## Verification

```bash
PYTHONPATH=scripts python -X utf8 scripts/verify-loader-template.py --root .
PYTHONPATH=scripts python -X utf8 scripts/verify-loader-template.py \
  --root . --manager-apk android/manager/app/build/outputs/apk/release/app-release.apk
unzip -t android/manager/app/src/main/assets/release/modloader-template-10005.apk
```

## Tests

```bash
cd android/manager
bash ./gradlew :core:model:test :core:storage:test :core:apk:test :core:workshop:test \
  :core:steam-protocol:test :core:workshop-download:test :app:testDebugUnitTest
```

Build native host tests with `-DMODLOADER_BUILD_HOST_TESTS=ON -DMODLOADER_BACKEND_MODE=1`; run `ctest --test-dir native/build-host --output-on-failure` on a compatible host.

## Constraints

- ARM64 only; every native `PT_LOAD` uses `p_align=0x4000` and has no `TEXTREL`.
- The base APK is never modified; only signing is allowed.
- The unsigned loader split is signed later with the same device identity as the base and original splits.
- Native/template changes require rebuilding and structural validation, but no checked-in SHA-256 update.
- The Bootstrap and Manager ModStorage bridge must use protocol version 2.
