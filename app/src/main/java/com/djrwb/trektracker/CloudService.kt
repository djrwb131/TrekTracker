package com.djrwb.trektracker

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.widget.Toast
import android.util.Base64
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.InputStream
import java.lang.Math.pow
import java.security.*
import java.security.cert.CertificateFactory
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec

// TODO: Fix encryption problems (how to deal with salt?)
class CloudService: Service() {
    private val KEY_ALIAS = "TrekTrackerDeviceID"
    private val mBinder = CloudBinder()

    private var mKey: SecretKey? = null
    private lateinit var mKeySpec: PBEKeySpec
    private lateinit var mCipher: Cipher
    private var mPrivateKey: KeyStore.PrivateKeyEntry? = null
    private fun createIV(): IvParameterSpec{
        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)
        return IvParameterSpec(iv)
    }
    // Packet layout:
    // 0-15: IV bytes
    // 16-47: salt
    // 48-79: RSA-signed SHA-256
    // 80-?: Data
    private fun encrypt(data: ByteArray): ByteArray {
        val lIVSpec = createIV()
        mCipher.init(Cipher.ENCRYPT_MODE,mKey,lIVSpec)
        val encrypted = mCipher.doFinal(data)
        val hash = RSAsign(encrypted)
        Timber.i("Hash length: ${hash.size}")
        return lIVSpec.iv + mKeySpec.salt + hash.size.toByte() + hash + encrypted
    }
    private fun toBytes(i: Int,bigEndian: Boolean = false): ByteArray {
       val r = ByteArray(4)
        r[0] = ((i and 0xFF000000.toInt()) ushr 24).toByte()
        r[1] = ((i and 0x00FF0000) ushr 16).toByte()
        r[2] = ((i and 0x0000FF00) ushr 8).toByte()
        r[3] = ((i and 0x000000FF) ushr 0).toByte()
        if(!bigEndian) return r else return r.reversedArray()
    }
    private fun fromBytes(l: ByteArray, bigEndian: Boolean = false): Int {
        var r = 0
        val b = if(bigEndian) l else l.reversedArray()
        for(i in b.indices) {
            r += b[i]*(pow(2.0,i*8.0)).toInt()
        }
        return r
    }
    private fun RSAsign(data: ByteArray): ByteArray {
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(mPrivateKey!!.privateKey)
            update(data)
            sign()
        } as ByteArray
    }
    private fun regenKeySpec(password: CharArray?, salt: ByteArray?) {
        mKeySpec = PBEKeySpec(password?:mKeySpec.password,salt?:mKeySpec.salt,1024,256)
        mKey = SecretKeyFactory.getInstance("PBKDF2withHmacSHA1").generateSecret(mKeySpec)
    }
    private fun decrypt(data: ByteArray): ByteArray? {
        val lIVSpec = IvParameterSpec(data.copyOfRange(0,16))
        regenKeySpec(null,data.copyOfRange(16,48))
        val lHashLen = data[48]
        val lHash = data.copyOfRange(49,49+lHashLen)
        val lData = data.copyOfRange(49+lHashLen,data.size)
        if(!Signature.getInstance("SHA256withECDSA").run {
            initVerify(mPrivateKey!!.certificate)
            update(lData)
            verify(lHash)
        }) {
            Timber.e("Data didn't verify :(")
            return null
        }
        return mCipher.doFinal(lData)
    }
    private fun retrieveSalt(): ByteArray {
        val ret = ByteArray(32)
        SecureRandom().nextBytes(ret)
        return ret
    }
    fun sendToCloud(pLocationDataList: List<LocationData>) {
        if(mKey == null) {
            Toast.makeText(this,"Please set a password!",Toast.LENGTH_LONG)
            Timber.e("Refusing to send unencrypted data! Please set a password.")
            return
        }
        Timber.i("Encrypting round trip...")
        val JSON = Gson()
        val before = JSON.toJson(pLocationDataList)
        val encrypted = (encrypt(before.toByteArray()))
        val b64_encrypted = Base64.encode(encrypted,Base64.DEFAULT)
        val after = decrypt(Base64.decode(b64_encrypted,Base64.DEFAULT))

    }
    fun retrieveFromCloud(pTripID: Int): List<LocationData>? {
        return null
    }
    fun setPassword(password: CharArray) {
        regenKeySpec(password,retrieveSalt())
    }
    private fun initCrypto() {
        mCipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        if(keyStore.containsAlias(KEY_ALIAS)) {
            Timber.i("Grabbing existing key pair")
            mPrivateKey = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
            if(mPrivateKey == null) {
                Timber.e("Couldn't retrieve RSA private key entry from keystore...")
            } else {
                if(mPrivateKey!!.privateKey.algorithm != KeyProperties.KEY_ALGORITHM_EC) {
                    Timber.e("Private Key for $KEY_ALIAS was of algorithm ${mPrivateKey!!.privateKey.algorithm}")
                    Timber.e("Recreating as ${KeyProperties.KEY_ALGORITHM_EC}")
                    mPrivateKey = null
                }
            }
        }
        if(mPrivateKey == null) {
            Timber.i("Generating new key pair")
            GlobalScope.launch {
                val pair = generateRSA()
                mPrivateKey = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                if (mPrivateKey == null) {
                    Timber.e("Couldn't retrieve RSA private key entry from keystore...")
                }
            }
        }
    }
    private fun generateRSA(): KeyPair {
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore")
        val paramSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).run {
            setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA512)
            build()
        }
        gen.initialize(paramSpec)
        return gen.generateKeyPair()
    }
    inner class CloudBinder : Binder() {
        fun getService(): CloudService = this@CloudService
    }
    private fun registerWithCloud(){
        //TODO: send certificate to ensure system can read signatures
        //TODO: get a CA going server-side to allow for registering of devices
    }
    override fun onBind(intent: Intent?): IBinder? {
        Timber.i("onBind called")
        initCrypto()
        //registerWithCloud()
        return mBinder
    }
}