package e2e

import java.security.KeyPairGenerator
import java.util.Base64

class Example08CryptographyTest extends E2ESuite:

  // The crypto component is injected only for this fixture (other examples don't
  // mount /keys, so it can't live in the shared components/ dir).
  private val cryptoComponent =
    """apiVersion: dapr.io/v1alpha1
      |kind: Component
      |metadata:
      |  name: localstorage
      |spec:
      |  type: crypto.dapr.localstorage
      |  version: v1
      |  metadata:
      |    - name: path
      |      value: /keys
      |""".stripMargin

  // Generate a fresh RSA key into a world-readable temp dir (daprd runs as a
  // different uid inside the container and must be able to read the key file).
  private def generateKeysDir(): os.Path =
    val dir = os.temp.dir(prefix = "dapr-e2e-crypto-keys")
    os.perms.set(dir, "rwxr-xr-x")
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val kp = kpg.generateKeyPair()
    val b64 = Base64.getMimeEncoder(64, Array[Byte]('\n')).encodeToString(kp.getPrivate.getEncoded)
    val pem = s"-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"
    val keyFile = dir / "rsa-key"
    os.write(keyFile, pem)
    os.perms.set(keyFile, "rw-r--r--")
    dir

  val infra = OneShotInfra(
    appId = "e2e-crypto",
    composeFileName = "docker-compose.08-crypto.yml",
    extraComponents = Map("cryptostore.yaml" -> cryptoComponent),
    extraEnv = () => Map("CRYPTO_KEYS_PATH" -> generateKeysDir().toString),
  )
  override def munitFixtures = List(infra)

  test("encrypt/decrypt round-trips text and raw bytes via crypto.dapr.localstorage") {
    val out = infra.run(jarModule = "cryptography", mainClass = "cryptography.run")
    assert(out.contains("decrypted:        the quick brown fox  ✓"), clue(out))
    assert(out.contains("raw-bytes round-trip: true  ✓"), clue(out))
  }
