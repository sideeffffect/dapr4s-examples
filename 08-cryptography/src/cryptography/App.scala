package cryptography

import dapr4s.*
import dapr4s.derivation.*
import scala.collection.immutable.ArraySeq

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

// The crypto key is described as a trait; dapr4s.derivation implements the encrypt side.
// Each method maps (via @name) to the CryptoKeyName; the plaintext type picks the overload
// (String → encryptString, ArraySeq[Byte] → encrypt). Decryption keeps the direct API.
trait RsaCipher:
  @name("rsa-key") def encryptText(plaintext: String, algorithm: KeyWrapAlgorithm)(using CryptoCapability): ArraySeq[Byte]
  @name("rsa-key") def encryptBytes(plaintext: ArraySeq[Byte], algorithm: KeyWrapAlgorithm)(using
      CryptoCapability,
  ): ArraySeq[Byte]
object RsaCipher extends Crypto.Derived[RsaCipher]

object CryptographyDemoApp:
  def apply()(using DaprCapability): CryptoResult =
    DaprCapability.crypto(CryptoComponent):
      val cipher5 = RsaCipher.derive
      val plaintext = "the quick brown fox"
      val cipher = cipher5.encryptText(plaintext, KeyWrapAlgorithm.Rsa)
      val decrypted = CryptoCapability.decryptString(cipher)

      val data = Charsets.encodeString("payload-bytes", Charsets.Utf8)
      val cipherBytes = cipher5.encryptBytes(data, KeyWrapAlgorithm.Rsa)
      val bytesRoundTrip = CryptoCapability.decrypt(cipherBytes) == data

      CryptoResult(plaintext, cipher.size, decrypted, bytesRoundTrip)
