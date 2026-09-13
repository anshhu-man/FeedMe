# Native private-storage cryptography integration notes

13 September 2026 · source/API design audit, not a native security validation result.

Use Android's `AndroidKeyStore` with `javax.crypto` directly, and Apple's CryptoKit through `dev.whyoleg.cryptography:cryptography-provider-cryptokit:0.6.0`. Keep encryption behind the shared storage cipher boundary. Native vaults retain keys; SQLite receives encrypted payload bytes and opaque lookup values. Do not silently replace an unavailable vault/provider with plaintext persistence.

## Verified library choice and compatibility

Upstream identifies **0.6.0**, published 2 April 2026, as the latest release at this audit. It was built with Kotlin 2.3.20; its setup guide requires Kotlin 2.3.0 or later. The repository pins Kotlin 2.3.21, so the documented Kotlin requirement is satisfied. This is source-level compatibility evidence, not proof that this application's Apple framework links. [Release notes](https://github.com/whyoleg/cryptography-kotlin/releases), [setup prerequisites](https://whyoleg.github.io/cryptography-kotlin/getting-started/).

| Provider | AES-GCM | HMAC-SHA256 | Recommendation |
| --- | --- | --- | --- |
| Android platform JCA + `AndroidKeyStore` | Yes | Yes | Use native non-exportable keys directly |
| `cryptography-provider-apple:0.6.0` (CommonCrypto) | No | Yes | Does not meet this store's AEAD requirement |
| `cryptography-provider-cryptokit:0.6.0` | Yes, 128-bit tag | Yes | Explicit iOS selection |

The maintained [provider matrix](https://whyoleg.github.io/cryptography-kotlin/primitives/operations/) distinguishes Apple/CommonCrypto from CryptoKit. The old `/providers/cryptokit/` page still describes 0.5.0 and says a 96-bit tag; do not copy that stale statement. Current AES-GCM API documents a **96-bit nonce and a 128-bit default authentication tag**. [AES-GCM API](https://whyoleg.github.io/cryptography-kotlin/api/cryptography-core/dev.whyoleg.cryptography.algorithms/-a-e-s/-g-c-m/), [cipher API](https://whyoleg.github.io/cryptography-kotlin/api/cryptography-core/dev.whyoleg.cryptography.algorithms/-a-e-s/-g-c-m/-key/cipher.html).

No custom AES, GHASH, HMAC, or CommonCrypto private-symbol bridge is needed. A direct Swift CryptoKit adapter is a viable alternative if the framework integration requires it; that would change the bridge/build boundary, not the cryptographic scheme. Do not introduce that second implementation merely to avoid an unrun native build.

## Concrete iOS integration

Add `cryptography-core:0.6.0` and `cryptography-provider-cryptokit:0.6.0` to the storage module's `iosMain` dependencies. Common code need not depend on this library when it sees only the application's cipher interface. Resolve the provider explicitly as `CryptographyProvider.CryptoKit`. Avoid a runtime provider fallback. [Apple provider setup](https://whyoleg.github.io/cryptography-kotlin/getting-started/providers/apple/).

The following calls are documented in version 0.6.0. `aesBytes` and `indexBytes` are distinct, 32-byte secrets loaded from the native vault; `aad`, `indexInput`, and the versioned storage envelope are application protocol values, not library-defined formats:

```kotlin
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.providers.cryptokit.CryptoKit

val provider = CryptographyProvider.CryptoKit
val aesKey = provider.get(AES.GCM).keyDecoder()
    .decodeFromByteArrayBlocking(AES.Key.Format.RAW, aesBytes)
val cipher = aesKey.cipher()
val sealed = cipher.encryptBlocking(plaintext, associatedData = aad)
val opened = cipher.decryptBlocking(sealed, associatedData = aad)

val indexKey = provider.get(HMAC).keyDecoder(SHA256)
    .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, indexBytes)
val opaqueIndex = indexKey.signatureGenerator()
    .generateSignatureBlocking(indexInput)
```

The provider extension's package was checked in [upstream provider source](https://github.com/whyoleg/cryptography-kotlin/blob/main/cryptography-providers/cryptokit/src/commonMain/kotlin/CryptoKitCryptographyProvider.kt); compilation against the pinned artifact remains a validation gate. The decoder has an explicit blocking method; the HMAC decoder takes the digest. [Decoder API](https://whyoleg.github.io/cryptography-kotlin/api/cryptography-core/dev.whyoleg.cryptography.materials/-decoder/index.html), [HMAC API](https://whyoleg.github.io/cryptography-kotlin/api/cryptography-core/dev.whyoleg.cryptography.algorithms/-h-m-a-c/), [signature API](https://whyoleg.github.io/cryptography-kotlin/api/cryptography-core/dev.whyoleg.cryptography.operations/-signature-generator/index.html).

Default encryption generates and prefixes a fresh nonce: `[12-byte nonce | ciphertext | 16-byte tag]`. Decrypt uses that same combined representation and the separately reconstructed AAD. Leave nonce generation to the provider. The blocking overloads fit a synchronous SQLite transaction boundary, but run the entire storage operation on an appropriate background dispatcher. [AEAD guide](https://whyoleg.github.io/cryptography-kotlin/primitives/operations/aead/), [blocking cipher API](https://whyoleg.github.io/cryptography-kotlin/api/cryptography-core/dev.whyoleg.cryptography.operations/-iv-authenticated-cipher/index.html).

CryptoKit uses Swift libraries supplied with full Xcode. For nonstandard Xcode locations, upstream 0.6.0 supplies `id("dev.whyoleg.cryptography") version "0.6.0"` and `cryptography { configureSwiftLinkerOpts = true }`. Apply the configuration to modules that own the final Apple binaries, including the shared application framework when necessary. Verify simulator and device linkage; configuration in a library with no final binaries may not cover its consumer. [Xcode/Swift guidance](https://whyoleg.github.io/cryptography-kotlin/getting-started/troubleshooting/xcode-compatibility/).

## Native vault requirements

### Android

Generate two independent 256-bit keys using `KeyGenerator.getInstance(algorithm, "AndroidKeyStore")`, under separate aliases and explicit account/environment/install-generation ownership:

- AES: `KEY_ALGORITHM_AES`, `PURPOSE_ENCRYPT or PURPOSE_DECRYPT`, GCM, `ENCRYPTION_PADDING_NONE`, randomized encryption required.
- Index: `KEY_ALGORITHM_HMAC_SHA256`, `PURPOSE_SIGN`, key size 256.

Encrypt using a new `Cipher.getInstance("AES/GCM/NoPadding")`, initialize for encryption with the stored key and let it generate its IV, call `updateAAD(aad)`, then `doFinal(plaintext)`. Store its 12-byte IV with the result. For decryption initialize with `GCMParameterSpec(128, iv)`, supply the identical AAD, then authenticate with `doFinal`. Compute indexes with a fresh `Mac.getInstance("HmacSHA256")` initialized with the index key. These are platform API operations, with AES/HMAC Keystore examples in [KeyGenParameterSpec](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec).

Keys remain non-exportable through the Android Keystore API. Hardware residency and StrongBox are separate device-dependent properties; do not claim either without inspecting `KeyInfo` on tested destinations. API 26 satisfies the API-23 KeyGenParameterSpec floor. Key access and crypto run off the UI thread. [Android Keystore security model](https://developer.android.com/privacy-and-security/keystore).

### iOS

Persist the two random 32-byte keys as Keychain generic-password items using `SecItemAdd` / `SecItemCopyMatching` / `SecItemDelete`. Use an application-owned service plus an unambiguous purpose/scope/generation account key. Set `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` and `kSecAttrSynchronizable = false`; do not set a broader shared access group. Apple documents generic-password storage for CryptoKit keys that lack native `SecKey` counterparts. [Apple key storage sample](https://developer.apple.com/documentation/cryptokit/storing-cryptokit-keys-in-the-keychain?changes=l_1_1).

`AfterFirstUnlockThisDeviceOnly` denies access until the first unlock after restart, then permits background access until the next restart. It prevents migration to another device. It does **not** deny access on every later screen lock. [Keychain accessibility semantics](https://developer.apple.com/documentation/security/ksecattraccessibleafterfirstunlockthisdeviceonly?changes=_1).

This iOS design loads secret bytes into the application to create CryptoKit symmetric keys. It is protected persistent key storage, not non-exportable Secure Enclave AES. Limit copies and lifetimes, clear byte arrays when owned and safe to do so, and do not promise total memory zeroization across Kotlin/Native/Swift copies. Apple distinguishes symmetric keys from supported Secure Enclave public-key operations. [CryptoKit overview](https://developer.apple.com/documentation/cryptokit), [SymmetricKey API](https://developer.apple.com/documentation/cryptokit/symmetrickey?changes=_3).

## Shared store protocol requirements

These are FeedMe design requirements, not claims supplied by the libraries:

1. Encode AAD with explicit versioning and length-prefixed fields: storage purpose, environment, principal kind/ID, record identity, and revision. Reconstruct it from trusted call context plus validated row metadata. Avoid delimiter concatenation with ambiguous boundaries.
2. Use a separate HMAC key and distinct domain labels for scope identifiers and record identifiers. Persist full 32-byte digests or their lossless encoding. HMAC masks enumerable names; it still reveals equality, row counts, and access patterns. It does not encrypt the SQLite schema.
3. Reject short, unsupported-version, over-limit or unauthentic ciphertext. Never return partial plaintext or reinterpret failed ciphertext as legacy plaintext. A key lookup error, missing key for an existing store, duplicate vault item, inaccessible vault, or authentication failure must be a typed storage failure.
4. Separate explicit first-store creation from reopening existing encrypted state. Reopening must never generate substitute keys. Handle create races by re-reading the winning vault item; do not overwrite it. Delete exact owned entries only.
5. Bind a store instance to an account/environment and session generation. Serialize close/logout/key deletion with operations; invalidate caller leases before cleanup. Reject late results after logout/account switch. Prefer per-scope keys if logout is intended to cryptographically erase one account without destroying another's data.
6. AAD revision binding prevents moving ciphertext to a different declared revision. It does not detect restoring an entire older, authentic database with matching older revisions. Record this rollback limitation; do not describe local CAS as an anti-rollback authority.
7. Keep database files, journal/WAL files and temporary files in app-private non-backup storage as configured by each platform. Ensure new writes reach SQLite only after encryption. An encryption layer does not scrub historical plaintext files from an earlier implementation.

## Validation gates

No Gradle build, dependency install, native simulator/device run, account setup or provider configuration was performed for this audit. The iOS shell already records that active Command Line Tools cannot build the target; full Xcode is required. [iOS scaffold evidence](../apps/ios/README.md).

Before promoting this boundary beyond a development foundation, record evidence for:

- Dependency resolution, exact CryptoKit extension import, Kotlin metadata compatibility, and linked `iosArm64` / `iosSimulatorArm64` application binaries at the iOS 16 deployment floor.
- Native roundtrip/reopen; random nonces; changed payload/tag/AAD/scope/key/revision rejection; cross-account lookup isolation; no sensitive plaintext in SQLite/WAL/journal/exception logs.
- Android API 26 plus current device tests; Keychain before/after first unlock; exact key deletion; key invalidation/missing-key handling; app backup/restore and install-generation handling; no automatic fresh-key replacement over old encrypted state.
- Concurrent creation, transaction rollback, process death during commit/logout, account A → B switching, and stale in-flight operation rejection.

Shared/JVM tests can verify framing, storage transactions and failure propagation; they cannot establish Keystore, Keychain, Swift linkage, hardware residency or physical-device lifecycle behavior.
