# aosp-node-pty

[**English**](README.md) · [中文](README_zh.md)

The **[node-pty](https://www.npmjs.com/package/node-pty) native addon for Android, packaged as an AAR** — a cross-compiled `libpty_node.so` shared library (arm64-v8a) that an in-process Node.js runtime ([`aosp-node-runtime`](https://github.com/fellow99/aosp-node-runtime)'s `libnode.so`) loads via N-API to provide pseudoterminal (`forkpty`/`openpty`) capability.

This repository contains **no embedding API and no JavaScript wrapper** — only the native binary's reproducible build, the ELF gates, and the publishing pipeline. It is a strict **companion artifact** of `aosp-node-runtime`.

## Versioning: it equals the Node version

The artifact version is **always identical** to the paired Node.js / `aosp-node-runtime` version. There is no independent semver.

| Runtime | PTY (this repo) |
|---|---|
| `io.github.fellow99:node-runtime:24.22.1` | `io.github.fellow99:node-pty:24.22.1` |

Cross-major compatibility is explicitly **not** supported (NODE_MODULE_VERSION differs). The two dependencies MUST resolve to the exact same version. Current release: `24.22.1` (NODE_MODULE_VERSION 137).

## Usage

```kotlin
// build.gradle.kts
dependencies {
    val nodeVer = "24.22.1" // runtime and pty MUST be identical
    runtimeOnly("io.github.fellow99:node-runtime:$nodeVer")
    runtimeOnly("io.github.fellow99:node-pty:$nodeVer")
}
```

After unpacking, both live in the APK's `nativeLibraryDir`: `libnode.so` and `libpty_node.so`. The pty AAR bundles **only** `libpty_node.so`; it never ships `libnode.so` or `libc++_shared.so`.

## Loading contract

The AAR deploys `libpty_node.so` to the read-only `nativeLibraryDir` (the only SELinux-compliant executable location when `useLegacyPackaging=false`). Upstream node-pty's JavaScript locates the addon at `build/Release/pty.node`, so a consumer must install a location adapter **before** `require('node-pty')`, for example:

```kotlin
// Preload by soname through the app linker namespace (preferred),
// then let node-pty's native binding resolve to the already-loaded library.
System.loadLibrary("pty_node")
```

…or, on the Node side, override node-pty's addon resolution to point at the absolute
`<nativeLibraryDir>/libpty_node.so` path injected by the host bridge. Loading succeeds;
`forkpty` then works (`unlockpt/grantpt → setsid → TIOCSCTTY → exec shell`, handled by node-pty).

If the binary is missing, the ABI does not match, or SELinux denies execution, consumers should report the failure structurally — never silently fall back to a fake terminal.

## What is in the AAR

```
jni/arm64-v8a/libpty_node.so    # node-pty N-API addon (ET_DYN, no PT_INTERP, DT_NEEDED libnode.so)
```

Requirements: `minSdk 24` · ABI `arm64-v8a`.

## How it is built

The binary is **not committed** and is never built by hand. [`build-pty-android.yml`](.github/workflows/build-pty-android.yml) runs on GitHub Actions:

1. Download the paired `libnode.so` **for link-time use only** from the matching `aosp-node-runtime` GitHub Release (the build fails hard if that version does not exist).
2. Fetch the unmodified `node-pty` npm source, the matching `node-v<version>-headers.tar.gz`, and `node-addon-api` via [`scripts/fetch-node-pty-source.mjs`](scripts/fetch-node-pty-source.mjs).
3. Cross-compile `src/unix/pty.cc` with Android NDK r28c via [`scripts/build-pty-android.sh`](scripts/build-pty-android.sh):
   - no `NODE_ADDON_API_ENABLE_MAYBE`,
   - `-Wl,--no-as-needed -l:libnode.so` so the product records `DT_NEEDED libnode.so`,
   - output named `libpty_node.so`, no `-lutil` (bionic provides `forkpty` in libc).
4. `llvm-strip --strip-unneeded`, then run the ELF gates.
5. Publish `node-pty-v<version>-android-arm64-v8a.tar.gz` to the GitHub Release `v<version>`.

Trigger a build manually:

```bash
gh workflow run build-pty-android.yml -f node_version=24.22.1 -f node_pty_version=1.1.0 -f android_api=24
```

## Locking and verification

[`pty.lock.json`](pty.lock.json) is the single source of truth for the paired Node version, NODE_MODULE_VERSION, binary SHA-256, gates, and provenance.

```bash
node tools/provision.mjs --check          # verify the deployed libpty_node.so against the lock
node tools/provision.mjs --inspect <so>   # print the ELF gate report for a binary
node tools/provision.mjs --out node-pty/src/main/jniLibs --from <so>
```

Exit codes: `0` all gates pass · `2` a gate failed (deploy rejected) · `1` usage/IO error.

Gates include `ET_DYN`, no `PT_INTERP`, AArch64, **`DT_NEEDED` contains `libnode.so`**, undefined `napi_*` symbols (resolved by the engine at load time), 16 KB page alignment, and lock SHA/size match.

## Repository layout

```
aosp-node-pty/
├── node-pty/                 # Android library module (the AAR container)
│   └── src/main/jniLibs/     # libpty_node.so deployed here (git-ignored; CI/provision)
├── scripts/
│   ├── fetch-node-pty-source.mjs  # node-pty npm source + Node headers + node-addon-api
│   └── build-pty-android.sh       # NDK cross-compile (hardens the five known pitfalls)
├── tools/provision.mjs       # deploy binary + ELF / DT_NEEDED gates
├── pty.lock.json             # paired version + SHA-256 + gates + provenance
└── .github/workflows/build-pty-android.yml
```

## License

[MIT](LICENSE). node-pty (MIT, Microsoft), node-addon-api (MIT), and Node-API/Node.js (MIT) — see [`node-pty/THIRD-PARTY-NOTICES.md`](node-pty/THIRD-PARTY-NOTICES.md).
