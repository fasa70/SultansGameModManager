# Official Android compatibility validation

## Supported loader contract

The supported template is an unsigned same-package split for the ARM64 Android 1.0.5 profile. It identifies package `com.gametree.sultan.pd`, split `modloader`, version code `10005`, and version name `1.0.5`. Its native payload is stored at `assets/modloader/arm64-v8a/modloader.bin` without ZIP compression.

Native and template contents are intentionally not pinned by checked-in SHA-256 values. Rebuilding native code therefore does not require changing source constants, test fixtures or release metadata. The exact bytes may be printed as ephemeral build provenance but are not runtime patch gates.

Bridge calls are gated by the loader revision: the Manager sends `expectedRevision` with every `ModStorageProvider` call, and the provider compares it against the revision packaged in its own split (`assets/modloader/revision`, currently 2). There is no separate protocol version number. See the [build guide](build.md#loader-revision) for the authoritative description and increment criteria.

## Runtime and release validation

Validation must cover:

- ARM64/AArch64 native output;
- every `PT_LOAD` segment aligned to `0x4000`;
- no `TEXTREL`;
- readable ZIP/APK with no duplicate entries;
- required manifest, resources, DEX and native entries;
- unsigned template with native entry `ZIP_STORED` and non-empty;
- package, split, and version identity;
- the provider contract — both method groups (`listMods`/`syncMod`/`removeManagedMod` for Mods, `listSaveUsers`/`listSaveFiles`/`readSave`/`writeSave` for saves) — plus the exported `Theme.NoDisplay` kickstart activity in the `:modstorage` process, and the revision consistency described above;
- Manager APK embedding the exact staged template bytes;
- device-local v1/v2 signing, payload preservation and complete final split installation verification.

The Manager performs structural checks before signing, then verifies the signed loader's v1/v2 signatures, device certificate, payload and final split set. Native content changes are accepted when these structural and signing contracts remain valid.

## Device evidence

The official native backend has been validated with UI reveal, URI/Texture and TMP glyph compatibility hooks (historical result; the device and Android version were not recorded at the time). The supported release workflow must rebuild the native artifact and rerun the ELF, template structure and instrumentation checks after native or Bootstrap changes. Record device model, Android version, and date when adding new evidence.

## Commands

The release pipeline creates ephemeral template and metadata files under `android/manager/app/build/release-stage`:

```bash
bash scripts/build-release.sh
PYTHONPATH=scripts python -X utf8 scripts/verify-loader-template.py \
  --stage android/manager/app/build/release-stage/publish \
  --manager-apk android/manager/app/build/outputs/apk/release/app-release.apk
```

On a compatible device, run the Android instrumentation suite and a complete migration/install smoke test after native or Bootstrap changes.

## Release artifacts

The loader template and structural metadata are generated for each release build and are intentionally not tracked in the source repository. Publish the Manager APK, optional standalone loader template, metadata and SHA-256 values as external release-page artifacts.

Certificate fingerprints and ordinary APK/mod integrity digests remain separate mechanisms and are not part of the native/template pin policy.

## Rebuild policy

When native changes, rebuild and rerun structural, native ELF, signing and instrumentation validation. Do not edit a native/template SHA-256 in project files.

This document states the contracts and validation checklist only; the commands live in the [build guide](build.md), which is authoritative for them. See also [architecture](architecture.md) for the Manager-side data paths that exercise these contracts.
