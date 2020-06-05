package misk.crypto

import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.KmsClient
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.aead.KmsEnvelopeAead
import com.google.crypto.tink.daead.DeterministicAeadKeyTemplates
import com.google.crypto.tink.hybrid.HybridKeyTemplates
import com.google.crypto.tink.mac.MacKeyTemplates
import com.google.crypto.tink.signature.SignatureKeyTemplates
import com.google.inject.CreationException
import com.google.inject.Guice
import com.google.inject.Injector
import misk.config.MiskConfig
import misk.config.Secret
import misk.environment.DeploymentModule
import misk.logging.LogCollector
import misk.logging.LogCollectorService
import misk.testing.MiskTest
import misk.testing.MiskTestModule
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.util.Base64

@MiskTest(startService = true)
class CryptoModuleTest {
  @Suppress("unused")
  @MiskTestModule
  val module = CryptoTestModule()

  @Test
  fun testImportAeadKey() {
    val keyHandle = KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM)
    val injector = getInjector(listOf(Pair("test", keyHandle)))
    val testKey = injector.getInstance(AeadKeyManager::class.java)["test"]
    assertThat(testKey).isNotNull
  }

  @Test
  fun testImportMacKey() {
    val keyHandle = KeysetHandle.generateNew(MacKeyTemplates.HMAC_SHA256_256BITTAG)
    val injector = getInjector(listOf(Pair("test-mac", keyHandle)))
    val mac = injector.getInstance(MacKeyManager::class.java)["test-mac"]
    assertThat(mac).isNotNull
  }

  @Test
  fun testImportDigitalSignature() {
    val keyHandle = KeysetHandle.generateNew(SignatureKeyTemplates.ED25519)
    val injector = getInjector(listOf(Pair("test-ds", keyHandle)))
    val keyManager = injector.getInstance(DigitalSignatureKeyManager::class.java)
    val signer = keyManager.getSigner("test-ds")
    val verifier = keyManager.getVerifier("test-ds")

    val data = "sign this".toByteArray()
    val signature = signer.sign(data)
    assertThatCode { verifier.verify(signature, data) }
        .doesNotThrowAnyException()
  }

  @Test
  fun testImportHybridKey() {
    val keyHandle = KeysetHandle.generateNew(HybridKeyTemplates.ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM)
    val injector = getInjector(listOf(Pair("test-hybrid", keyHandle)))
    val hybridEncryptKeyManager = injector.getInstance(HybridEncryptKeyManager::class.java)
    val hybridDecryptKeyManager = injector.getInstance(HybridDecryptKeyManager::class.java)
    assertThat(hybridEncryptKeyManager).isNotNull
    assertThat(hybridDecryptKeyManager).isNotNull

    val plaintext = "plaintext".toByteArray()
    val encrypter = hybridEncryptKeyManager["test-hybrid"]
    assertThat(encrypter).isNotNull
    val ciphertext = encrypter.encrypt(plaintext, null)

    val decrypter = hybridDecryptKeyManager["test-hybrid"]
    assertThat(decrypter).isNotNull
    val decrypted = decrypter.decrypt(ciphertext, null)

    assertThat(plaintext).isEqualTo(decrypted)
  }

  @Test
  fun testImportOnlyPublicHybridKey() {
    val keysetHandle = KeysetHandle.generateNew(HybridKeyTemplates.ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM).publicKeysetHandle
    val injector = getInjector(listOf(Pair("test-hybrid", keysetHandle)))
    val hybridEncryptKeyManager = injector.getInstance(HybridEncryptKeyManager::class.java)
    assertThat(hybridEncryptKeyManager).isNotNull
    assertThat(hybridEncryptKeyManager["test-hybrid"]).isNotNull
  }

  @Test
  fun testMultipleKeys() {
    val aeadHandle = KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM)
    val macHandle = KeysetHandle.generateNew(MacKeyTemplates.HMAC_SHA256_256BITTAG)
    val injector = getInjector(listOf(Pair("aead", aeadHandle), Pair("mac", macHandle)))
    assertThat(injector.getInstance(AeadKeyManager::class.java)["aead"]).isNotNull
    assertThat(injector.getInstance(MacKeyManager::class.java)["mac"]).isNotNull
  }

  @Test
  fun testDuplicateNames() {
    getInjector(listOf()) // Call without args first to initialize primitives
    val key1 = KeysetHandle.generateNew(AeadKeyTemplates.AES256_CTR_HMAC_SHA256)
    val key2 = KeysetHandle.generateNew(AeadKeyTemplates.AES256_CTR_HMAC_SHA256)
    assertThatThrownBy { getInjector(listOf(Pair("aead", key1), Pair("aead", key2))) }
        .isInstanceOf(CreationException::class.java)
  }

  @Test
  fun testNoKeyLoaded() {
    val injector = getInjector(listOf())
    assertThatThrownBy { injector.getInstance(MacKeyManager::class.java)["not there"] }
            .isInstanceOf(KeyNotFoundException::class.java)
    assertThatThrownBy { injector.getInstance(AeadKeyManager::class.java)["not there either"] }
            .isInstanceOf(KeyNotFoundException::class.java)
  }

  @Test
  fun testLogsWarningWithoutEnvelopeKey() {
    val injector = getInjector(listOf())
    val lcs = injector.getInstance(LogCollectorService::class.java)
    lcs.startAsync()
    lcs.awaitRunning()

    val lc = injector.getInstance(LogCollector::class.java)

    val kh = KeysetHandle.generateNew(AeadKeyTemplates.AES256_CTR_HMAC_SHA256)
    val encryptedKey = generateObsoleteEncryptedKey(kh)
    val key = Key("name", KeyType.AEAD, encryptedKey)
    val client = injector.getInstance(KmsClient::class.java) // FakeKmsClient()
    val kr = KeyReader()
    kr.readKey(key, "aws-kms://some-uri", client)
    val out = lc.takeMessage()
    assertThat(out).contains("using obsolete key format")
  }

  @Test
  fun testAeadExtensionFunctions() {
    val keyHandle = KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM)
    val injector = getInjector(listOf(Pair("test", keyHandle)))
    val testKey = injector.getInstance(AeadKeyManager::class.java)["test"]

    // test with encryption context
    var encryptionContext: Map<String, String>? = mapOf("key1" to "value1")
    val plaintext = "Hello world!".encodeUtf8()
    var ciphertext = testKey.encrypt(plaintext, encryptionContext)
    assertThat(testKey.decrypt(ciphertext, encryptionContext)).isEqualTo(plaintext)
    assertThatThrownBy { testKey.decrypt(ciphertext, emptyMap()) }
        .hasMessage("encryption context doesn't match")
    assertThatThrownBy { testKey.decrypt(ciphertext, null) }
        .hasMessage("encryption context doesn't match")
    assertThatThrownBy { testKey.decrypt(ciphertext, mapOf("wrong key" to "wrong value")) }
        .hasMessage("encryption context doesn't match")

    // test with empty encryption context
    encryptionContext = emptyMap()
    ciphertext = testKey.encrypt(plaintext, encryptionContext)
    assertThat(testKey.decrypt(ciphertext, encryptionContext)).isEqualTo(plaintext)
    assertThat(testKey.decrypt(ciphertext, null)).isEqualTo(plaintext)
    assertThatThrownBy { testKey.decrypt(ciphertext, mapOf("wrong key" to "wrong value")) }
        .hasMessage("encryption context doesn't match")

    // test with no encryption context
    encryptionContext = null
    ciphertext = testKey.encrypt(plaintext, encryptionContext)
    assertThat(testKey.decrypt(ciphertext, encryptionContext)).isEqualTo(plaintext)
    assertThat(testKey.decrypt(ciphertext, emptyMap())).isEqualTo(plaintext)
    assertThatThrownBy { testKey.decrypt(ciphertext, mapOf("wrong key" to "wrong value")) }
        .hasMessage("encryption context doesn't match")

    // test compatibility with old schema
    val aad = byteArrayOf()
    val oldCiphertext = testKey.encrypt(plaintext.toByteArray(), aad)
    assertThat(testKey.decrypt(oldCiphertext.toByteString(), emptyMap())).isEqualTo(plaintext)
  }

  @Test
  fun testDaeadExtensionFunctions() {
    val keyHandle = KeysetHandle.generateNew(DeterministicAeadKeyTemplates.AES256_SIV)
    val injector = getInjector(listOf(Pair("test", keyHandle)))
    val daead = injector.getInstance(DeterministicAeadKeyManager::class.java)["test"]
    val plaintext = "plaintext".encodeUtf8()
    // with encryption context
    var encryptionContext: Map<String, String>? = mapOf("key1" to "value1")
    var ciphertext = daead.encryptDeterministically(plaintext, encryptionContext)
    assertThat(daead.decryptDeterministically(ciphertext, encryptionContext)).isEqualTo(plaintext)
    assertThatThrownBy { daead.decryptDeterministically(ciphertext, emptyMap())}
        .hasMessage("encryption context doesn't match")
    assertThatThrownBy { daead.decryptDeterministically(ciphertext, null)}
        .hasMessage("encryption context doesn't match")
    assertThatThrownBy { daead.decryptDeterministically(ciphertext, mapOf("wrong key" to "wrong value"))}
        .hasMessage("encryption context doesn't match")

    // test with empty encryption context
    encryptionContext = emptyMap()
    ciphertext = daead.encryptDeterministically(plaintext, encryptionContext)
    assertThat(daead.decryptDeterministically(ciphertext, encryptionContext)).isEqualTo(plaintext)
    assertThat(daead.decryptDeterministically(ciphertext, null)).isEqualTo(plaintext)
    assertThatThrownBy { daead.decryptDeterministically(ciphertext, mapOf("wrong key" to "wrong value")) }
        .hasMessage("encryption context doesn't match")

    // test with no encryption context
    encryptionContext = null
    ciphertext = daead.encryptDeterministically(plaintext, encryptionContext)
    assertThat(daead.decryptDeterministically(ciphertext, encryptionContext)).isEqualTo(plaintext)
    assertThat(daead.decryptDeterministically(ciphertext, emptyMap())).isEqualTo(plaintext)
    assertThatThrownBy { daead.decryptDeterministically(ciphertext, mapOf("wrong key" to "wrong value")) }
        .hasMessage("encryption context doesn't match")

    // test compatibility with old schema
    val aad = byteArrayOf()
    val oldCiphertext = daead.encryptDeterministically(plaintext.toByteArray(), aad)
    assertThat(daead.decryptDeterministically(oldCiphertext.toByteString(), emptyMap())).isEqualTo(plaintext)
  }

  @Test
  fun testHybridExtensionMethods() {
    val keyHandle = KeysetHandle.generateNew(HybridKeyTemplates.ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM)
    val injector = getInjector(listOf(Pair("test", keyHandle)))
    val hybridEncrypt = injector.getInstance(HybridEncryptKeyManager::class.java)["test"]
    val hybridDecrypt = injector.getInstance(HybridDecryptKeyManager::class.java)["test"]
    val plaintext = "plaintext".encodeUtf8()
    // with encryption context
    var encryptionContext: Map<String, String>? = mapOf("key1" to "value1")
    var ciphertext = hybridEncrypt.encrypt(plaintext, encryptionContext)
    assertThat(hybridDecrypt.decrypt(ciphertext, encryptionContext)).isEqualTo(plaintext)
    assertThatThrownBy { hybridDecrypt.decrypt(ciphertext, emptyMap())}
        .hasMessage("encryption context doesn't match")
    assertThatThrownBy { hybridDecrypt.decrypt(ciphertext, null)}
        .hasMessage("encryption context doesn't match")
    assertThatThrownBy { hybridDecrypt.decrypt(ciphertext, mapOf("wrong key" to "wrong value"))}
        .hasMessage("encryption context doesn't match")

    // test with empty encryption context
    encryptionContext = emptyMap()
    ciphertext = hybridEncrypt.encrypt(plaintext, encryptionContext)
    assertThat(hybridDecrypt.decrypt(ciphertext, encryptionContext)).isEqualTo(plaintext)
    assertThat(hybridDecrypt.decrypt(ciphertext, null)).isEqualTo(plaintext)
    assertThatThrownBy { hybridDecrypt.decrypt(ciphertext, mapOf("wrong key" to "wrong value")) }
        .hasMessage("encryption context doesn't match")

    // test with no encryption context
    encryptionContext = null
    ciphertext = hybridEncrypt.encrypt(plaintext, encryptionContext)
    assertThat(hybridDecrypt.decrypt(ciphertext, encryptionContext)).isEqualTo(plaintext)
    assertThat(hybridDecrypt.decrypt(ciphertext, emptyMap())).isEqualTo(plaintext)
    assertThatThrownBy { hybridDecrypt.decrypt(ciphertext, mapOf("wrong key" to "wrong value")) }
        .hasMessage("encryption context doesn't match")

    // test compatibility with old schema
    val aad = byteArrayOf()
    val oldCiphertext = hybridEncrypt.encrypt(plaintext.toByteArray(), aad)
    assertThat(hybridDecrypt.decrypt(oldCiphertext.toByteString(), emptyMap())).isEqualTo(plaintext)
  }

  @Test
  fun testMacExtensionMethods() {
    val keyHandle = KeysetHandle.generateNew(MacKeyTemplates.HMAC_SHA256_256BITTAG)
    val injector = getInjector(listOf(Pair("test", keyHandle)))
    val hmac = injector.getInstance(MacKeyManager::class.java)["test"]
    val message = "hello!"
    val tag = hmac.computeMac(message)
    assertThatCode { hmac.verifyMac(tag, message) }.doesNotThrowAnyException()
    assertThatThrownBy { hmac.verifyMac("not a base64 string", message) }
        .hasMessageContaining("invalid tag:")
    assertThatThrownBy {
      hmac.verifyMac(Base64.getEncoder().encodeToString("wrong tag!".toByteArray()), message)
    }.hasMessage("invalid MAC")
  }

  @Disabled
  @Test // Currently disabled since the env check is as well
  fun testRaisesInWrongEnv() {
    val plainKey = Key("name", KeyType.AEAD, MiskConfig.RealSecret(""))
    val kr = KeyReader()
    val client = FakeKmsClient()

    assertThatThrownBy {
      // kr.env = Environment.STAGING
      kr.readKey(plainKey, null, client)
    }.isInstanceOf(GeneralSecurityException::class.java)

    assertThatThrownBy {
      // kr.env = Environment.PRODUCTION
      kr.readKey(plainKey, null, client)
    }.isInstanceOf(GeneralSecurityException::class.java)
  }

  private fun getInjector(keyMap: List<Pair<String, KeysetHandle>>): Injector {
    val keys = keyMap.map {
      var keyType = KeyType.AEAD
      val keyTypeUrl = it.second.keysetInfo.getKeyInfo(0).typeUrl
      if (keyTypeUrl.endsWith("HmacKey")) {
        keyType = KeyType.MAC
      } else if (keyTypeUrl.endsWith("Ed25519PrivateKey")) {
        keyType = KeyType.DIGITAL_SIGNATURE
      } else if (keyTypeUrl.endsWith("EciesAeadHkdfPrivateKey")) {
        keyType = KeyType.HYBRID_ENCRYPT_DECRYPT
      } else if (keyTypeUrl.endsWith("EciesAeadHkdfPublicKey")) {
        keyType = KeyType.HYBRID_ENCRYPT
      } else if (keyTypeUrl.endsWith("AesSivKey")) {
        keyType = KeyType.DAEAD
      }
      Key(it.first, keyType, generateEncryptedKey(it.second))
    }
    val config = CryptoConfig(keys, "test_master_key")
    return Guice.createInjector(CryptoTestModule(), CryptoModule(config), DeploymentModule.forTesting())
  }

  private fun generateEncryptedKey(keyHandle: KeysetHandle): Secret<String> {
    val masterKey = FakeMasterEncryptionKey()
    val keyOutputStream = ByteArrayOutputStream()
    val kek = KmsEnvelopeAead(KeyReader.KEK_TEMPLATE, masterKey)
    keyHandle.write(JsonKeysetWriter.withOutputStream(keyOutputStream), kek)
    return object : Secret<String> {
      override val value: String
        get() = keyOutputStream.toString()
    }
  }

  private fun generateObsoleteEncryptedKey(keyHandle: KeysetHandle): Secret<String> {
    val masterKey = FakeMasterEncryptionKey()
    val keyOutputStream = ByteArrayOutputStream()
    keyHandle.write(JsonKeysetWriter.withOutputStream(keyOutputStream), masterKey)
    return object : Secret<String> {
      override val value: String
        get() = keyOutputStream.toString()
    }
  }
}