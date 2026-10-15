package im.flare.todo.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import im.flare.todo.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore("session")

data class SessionState(
    val serverUrl: String = BuildConfig.DEFAULT_SERVER_URL,
    val token: String? = null,
    val username: String? = null,
    val timezone: String = DEFAULT_TIMEZONE,
    /** 지문 인증으로만 풀리는 키로 암호화한 로그인 정보 (Base64) */
    val bioBlob: String? = null,
    val bioIv: String? = null,
    val bioOfferDeclined: Boolean = false,
    /** 상태표시줄에 띄울 카테고리 ID. [PIN_ALL] 은 카테고리와 무관한 전체 미완료 목록. */
    val pinnedIds: Set<Long> = emptySet(),
    /** Android 16 Live Update(상태표시줄 칩)로 띄울지 */
    val promoted: Boolean = true,
    val listFilter: String? = null,
) {
    val bioEnabled get() = bioBlob != null && bioIv != null

    companion object {
        const val DEFAULT_TIMEZONE = "Asia/Seoul"
        const val PIN_ALL = 0L
    }
}

/** 로그인 상태와 앱 설정. 기기 밖으로 백업되지 않는다. */
class SessionStore(context: Context) {

    private val ds = context.dataStore
    private val _state = MutableStateFlow(runBlocking { ds.data.first().toState() })
    val state: StateFlow<SessionState> = _state

    private suspend fun update(block: MutablePreferences.() -> Unit) {
        _state.value = ds.edit(block).toState()
    }

    suspend fun setServerUrl(url: String) = update { this[SERVER_URL] = url }

    suspend fun setLogin(token: String, user: User) = update {
        this[TOKEN] = TokenCrypto.encrypt(token)
        this[USERNAME] = user.username
        user.timezone?.takeIf { it.isNotBlank() }?.let { this[TIMEZONE] = it }
    }

    suspend fun clearToken() = update { remove(TOKEN) }

    suspend fun setTimezone(tz: String) = update { this[TIMEZONE] = tz }

    suspend fun setBiometric(blob: String, iv: String) = update {
        this[BIO_BLOB] = blob
        this[BIO_IV] = iv
    }

    suspend fun clearBiometric() = update {
        remove(BIO_BLOB)
        remove(BIO_IV)
        remove(BIO_DECLINED)
    }

    suspend fun setBioOfferDeclined() = update { this[BIO_DECLINED] = true }

    suspend fun setPinned(ids: Set<Long>) = update { this[PINNED] = ids.map(Long::toString).toSet() }

    suspend fun setPromoted(on: Boolean) = update { this[PROMOTED] = on }

    suspend fun setListFilter(json: String) = update { this[LIST_FILTER] = json }

    private fun Preferences.toState() = SessionState(
        serverUrl = this[SERVER_URL] ?: BuildConfig.DEFAULT_SERVER_URL,
        token = this[TOKEN]?.let(TokenCrypto::decrypt),
        username = this[USERNAME],
        timezone = this[TIMEZONE] ?: SessionState.DEFAULT_TIMEZONE,
        bioBlob = this[BIO_BLOB],
        bioIv = this[BIO_IV],
        bioOfferDeclined = this[BIO_DECLINED] ?: false,
        pinnedIds = this[PINNED].orEmpty().mapNotNull(String::toLongOrNull).toSet(),
        promoted = this[PROMOTED] ?: true,
        listFilter = this[LIST_FILTER],
    )

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val TOKEN = stringPreferencesKey("token")
        val USERNAME = stringPreferencesKey("username")
        val TIMEZONE = stringPreferencesKey("timezone")
        val BIO_BLOB = stringPreferencesKey("bio_blob")
        val BIO_IV = stringPreferencesKey("bio_iv")
        val BIO_DECLINED = booleanPreferencesKey("bio_offer_declined")
        val PINNED = stringSetPreferencesKey("pinned_category_ids")
        val PROMOTED = booleanPreferencesKey("pinned_promoted")
        val LIST_FILTER = stringPreferencesKey("list_filter")
    }
}

/** 액세스 토큰을 Android Keystore 키(AES-GCM)로 암호화해 저장한다. */
private object TokenCrypto {
    private const val ALIAS = "flare_token_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12

    // 같은 암호문을 상태를 읽을 때마다 다시 풀지 않도록 마지막 결과를 기억한다.
    private var cached: Pair<String, String?>? = null

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    @Synchronized
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        cached = enc to plain
        return enc
    }

    /** 키가 사라졌거나 값이 손상됐으면 null (= 로그아웃 상태) */
    @Synchronized
    fun decrypt(enc: String): String? {
        cached?.let { if (it.first == enc) return it.second }
        val plain = runCatching {
            val bytes = Base64.decode(enc, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
            }
            String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH))
        }.getOrNull()
        cached = enc to plain
        return plain
    }
}
