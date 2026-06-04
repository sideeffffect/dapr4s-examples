package cryptography

import dapr4s.*

// ── Capture-checked pure module ───────────────────────────────────────────────
// CryptoCapability is an ExclusiveCapability acquired by DaprCapability.crypto(...).
// It wraps the data key for `plaintext` with the named component key; decrypt reads
// the key reference embedded in the ciphertext, so it needs only the ciphertext.
// Payloads are immutable ArraySeq[Byte]; the *String helpers handle UTF-8 text.
// Runs against the `crypto.dapr.localstorage` component backed by an RSA key.
// ─────────────────────────────────────────────────────────────────────────────

val CryptoComponent = CryptoComponentName("localstorage")
val RsaKey = CryptoKeyName("rsa-key")

case class CryptoResult(
    plaintext: String,
    cipherSize: Int,
    decrypted: String,
    bytesRoundTrip: Boolean,
)

object CryptographyDemoApp:
  def apply()(using DaprCapability): CryptoResult =
    DaprCapability.crypto(CryptoComponent):
      val plaintext = "the quick brown fox"
      val cipher = CryptoCapability.encryptString(RsaKey, plaintext, KeyWrapAlgorithm.Rsa)
      val decrypted = CryptoCapability.decryptString(cipher)

      val data = Charsets.encodeString("payload-bytes", Charsets.Utf8)
      val cipherBytes = CryptoCapability.encrypt(RsaKey, data, KeyWrapAlgorithm.Rsa)
      val bytesRoundTrip = CryptoCapability.decrypt(cipherBytes) == data

      CryptoResult(plaintext, cipher.size, decrypted, bytesRoundTrip)
