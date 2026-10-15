package im.flare.todo.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import im.flare.todo.data.SessionStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BiometricException(message: String) : Exception(message)

/**
 * 지문 로그인.
 *
 * 서버 토큰은 24시간짜리라 지문만으로 다시 로그인하려면 아이디·비밀번호가 필요하다.
 * 이를 Android Keystore 의 "지문 인증을 통과해야만 쓸 수 있는" AES 키로 암호화해 두고,
 * 지문 인증에 성공했을 때만 복호화해서 로그인 API 를 호출한다.
 * 기기에 새 지문이 등록되면 키가 폐기되어 비밀번호로 다시 로그인해야 한다.
 */
class BiometricLogin(private val session: SessionStore) {

    @Serializable
    private data class Credentials(val u: String, val p: String)

    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** 지문 인증 후 로그인 정보를 암호화해 저장한다. 사용자가 취소하면 false. */
    suspend fun enroll(activity: FragmentActivity, username: String, password: String): Boolean {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, newKey()) }
        val authed = prompt(activity, "지문 로그인 등록", "지문을 인증하면 다음부터 지문으로 로그인합니다.", cipher) ?: return false
        val plain = Json.encodeToString(Credentials.serializer(), Credentials(username, password)).toByteArray()
        session.setBiometric(
            blob = Base64.encodeToString(authed.doFinal(plain), Base64.NO_WRAP),
            iv = Base64.encodeToString(authed.iv, Base64.NO_WRAP),
        )
        return true
    }

    /**
     * 지문 인증 후 저장된 (아이디, 비밀번호)를 돌려준다. 사용자가 취소하면 null.
     * 키가 무효화됐으면(지문 변경 등) 등록을 지우고 [BiometricException].
     */
    suspend fun unlock(activity: FragmentActivity): Pair<String, String>? {
        val state = session.state.value
        val blob = state.bioBlob
        val iv = state.bioIv
        if (blob == null || iv == null) throw BiometricException("지문 로그인이 설정되어 있지 않습니다.")
        val cipher = try {
            val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: throw UnrecoverableKeyException()
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disable()
            throw BiometricException("기기의 지문 정보가 바뀌어 지문 로그인이 해제되었습니다. 비밀번호로 로그인하세요.")
        } catch (e: UnrecoverableKeyException) {
            disable()
            throw BiometricException("지문 로그인 정보를 읽을 수 없습니다. 비밀번호로 로그인하세요.")
        }
        val authed = prompt(activity, "지문 로그인", state.username?.let { "$it 계정으로 로그인합니다." } ?: "", cipher)
            ?: return null
        val creds = try {
            Json.decodeFromString(Credentials.serializer(), String(authed.doFinal(Base64.decode(blob, Base64.NO_WRAP))))
        } catch (e: Exception) {
            disable()
            throw BiometricException("지문 로그인 정보를 읽을 수 없습니다. 비밀번호로 로그인하세요.")
        }
        return creds.u to creds.p
    }

    suspend fun disable() {
        runCatching { keyStore().deleteEntry(ALIAS) }
        session.clearBiometric()
    }

    private suspend fun prompt(activity: FragmentActivity, title: String, subtitle: String, cipher: Cipher): Cipher? =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(result.cryptoObject?.cipher)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!cont.isActive) return
                        when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_CANCELED -> cont.resume(null)

                            else -> cont.resumeWithException(BiometricException(errString.toString()))
                        }
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { if (subtitle.isNotEmpty()) setSubtitle(subtitle) }
                .setNegativeButtonText("취소")
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setConfirmationRequired(false)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun newKey(): SecretKey {
        runCatching { keyStore().deleteEntry(ALIAS) }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // 매번 지문 인증이 있어야 키를 쓸 수 있고, 지문이 새로 등록되면 키가 폐기된다.
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val ALIAS = "flare_biometric_login_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
