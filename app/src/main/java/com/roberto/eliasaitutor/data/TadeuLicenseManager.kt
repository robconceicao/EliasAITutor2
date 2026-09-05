package com.roberto.eliasaitutor.data

import android.content.Context
import com.roberto.eliasaitutor.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val APP_SLUG = "elias-ai-tutor"
private const val PREFS = "tadeu_apps_license"
private const val ACCESS_TOKEN = "access_token"
private const val REFRESH_TOKEN = "refresh_token"
private const val TOKEN_EXPIRES_AT = "token_expires_at"
private const val LICENSE_CACHE = "license_cache"
private const val LICENSE_CHECKED_AT = "license_checked_at"
private const val OFFLINE_TTL_MS = 24L * 60L * 60L * 1000L

data class LicensedFeature(
    val key: String,
    val limitValue: Int? = null,
    val limitUnit: String? = null,
)

data class TadeuLicense(
    val plan: String,
    val features: List<LicensedFeature>,
    val expiresAtMillis: Long?,
    val offline: Boolean = false,
) {
    fun hasFeature(key: String): Boolean =
        plan == "legacy" || features.any { it.key == key }

    fun limit(key: String): Int? = features.firstOrNull { it.key == key }?.limitValue
}

class TadeuLicenseException(message: String) : Exception(message)

class TadeuLicenseManager(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val configured: Boolean
        get() = BuildConfig.TADEU_APPS_URL.isNotBlank() &&
            BuildConfig.TADEU_APPS_SUPABASE_URL.isNotBlank() &&
            BuildConfig.TADEU_APPS_SUPABASE_ANON_KEY.isNotBlank()

    /**
     * Token público de sessão do próprio usuário para autenticar o handshake
     * Socket.IO no backend do Elias. Nunca expõe refresh token ou segredo de servidor.
     */
    fun currentAccessToken(): String? = prefs
        .getString(ACCESS_TOKEN, null)
        ?.takeIf { it.isNotBlank() }

    suspend fun signIn(email: String, password: String): TadeuLicense = withContext(Dispatchers.IO) {
        ensureConfigured()
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .toString()
            .toRequestBody(JSON)

        val request = Request.Builder()
            .url("${BuildConfig.TADEU_APPS_SUPABASE_URL.trimEnd('/')}/auth/v1/token?grant_type=password")
            .header("apikey", BuildConfig.TADEU_APPS_SUPABASE_ANON_KEY)
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw TadeuLicenseException("TADEU_AUTH_FAILED")
            saveAuth(JSONObject(raw))
        }

        fetchLicenseInternal(allowOfflineCache = false)
    }

    suspend fun restoreAndFetch(): TadeuLicense = withContext(Dispatchers.IO) {
        ensureConfigured()
        ensureValidAccessToken()
        fetchLicenseInternal(allowOfflineCache = true)
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
    }

    private fun ensureConfigured() {
        if (!configured) throw TadeuLicenseException("TADEU_NOT_CONFIGURED")
    }

    private fun saveAuth(payload: JSONObject) {
        val access = payload.optString("access_token")
        val refresh = payload.optString("refresh_token")
        val expiresIn = payload.optLong("expires_in", 3600L)
        if (access.isBlank() || refresh.isBlank()) throw TadeuLicenseException("TADEU_AUTH_INVALID_RESPONSE")

        prefs.edit()
            .putString(ACCESS_TOKEN, access)
            .putString(REFRESH_TOKEN, refresh)
            .putLong(TOKEN_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000L - 60_000L)
            .apply()
    }

    private fun ensureValidAccessToken() {
        val token = prefs.getString(ACCESS_TOKEN, null)
        val expiresAt = prefs.getLong(TOKEN_EXPIRES_AT, 0L)
        if (!token.isNullOrBlank() && expiresAt > System.currentTimeMillis()) return
        refreshSession()
    }

    private fun refreshSession() {
        val refresh = prefs.getString(REFRESH_TOKEN, null)
            ?: throw TadeuLicenseException("TADEU_AUTH_REQUIRED")

        val body = JSONObject()
            .put("refresh_token", refresh)
            .toString()
            .toRequestBody(JSON)
        val request = Request.Builder()
            .url("${BuildConfig.TADEU_APPS_SUPABASE_URL.trimEnd('/')}/auth/v1/token?grant_type=refresh_token")
            .header("apikey", BuildConfig.TADEU_APPS_SUPABASE_ANON_KEY)
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                prefs.edit().remove(ACCESS_TOKEN).remove(REFRESH_TOKEN).apply()
                throw TadeuLicenseException("TADEU_AUTH_REQUIRED")
            }
            saveAuth(JSONObject(raw))
        }
    }

    private fun fetchLicenseInternal(allowOfflineCache: Boolean): TadeuLicense {
        val token = prefs.getString(ACCESS_TOKEN, null)
            ?: throw TadeuLicenseException("TADEU_AUTH_REQUIRED")
        val request = Request.Builder()
            .url("${BuildConfig.TADEU_APPS_URL.trimEnd('/')}/api/apps/$APP_SLUG/license")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.code == 401 || response.code == 403) {
                    prefs.edit().remove(LICENSE_CACHE).apply()
                    throw TadeuLicenseException("TADEU_LICENSE_DENIED")
                }
                if (!response.isSuccessful) throw TadeuLicenseException("TADEU_LICENSE_HTTP_${response.code}")

                val json = JSONObject(raw)
                if (json.optString("license") != "active") {
                    throw TadeuLicenseException("TADEU_LICENSE_DENIED")
                }
                val parsed = parseLicense(json, offline = false)
                prefs.edit()
                    .putString(LICENSE_CACHE, raw)
                    .putLong(LICENSE_CHECKED_AT, System.currentTimeMillis())
                    .apply()
                return parsed
            }
        } catch (error: Exception) {
            if (error is TadeuLicenseException && error.message == "TADEU_LICENSE_DENIED") throw error
            if (allowOfflineCache) readOfflineCache()?.let { return it }
            throw error
        }
    }

    private fun readOfflineCache(): TadeuLicense? {
        val checkedAt = prefs.getLong(LICENSE_CHECKED_AT, 0L)
        if (checkedAt == 0L || System.currentTimeMillis() - checkedAt > OFFLINE_TTL_MS) return null
        val raw = prefs.getString(LICENSE_CACHE, null) ?: return null
        return try {
            val license = parseLicense(JSONObject(raw), offline = true)
            if (license.expiresAtMillis != null && license.expiresAtMillis <= System.currentTimeMillis()) null else license
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLicense(json: JSONObject, offline: Boolean): TadeuLicense {
        val featuresJson = json.optJSONArray("features") ?: JSONArray()
        val features = buildList {
            for (i in 0 until featuresJson.length()) {
                val item = featuresJson.getJSONObject(i)
                add(
                    LicensedFeature(
                        key = item.getString("key"),
                        limitValue = if (item.isNull("limitValue")) null else item.optInt("limitValue"),
                        limitUnit = if (item.isNull("limitUnit")) null else item.optString("limitUnit"),
                    )
                )
            }
        }
        val expiresAt = json.optString("expiresAt").takeIf { it.isNotBlank() && it != "null" }?.let {
            runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
        }
        return TadeuLicense(
            plan = json.optString("plan", "free"),
            features = features,
            expiresAtMillis = expiresAt,
            offline = offline,
        )
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
