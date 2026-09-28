package com.roberto.eliasaitutor.network

import android.content.Context
import com.roberto.eliasaitutor.data.TadeuLicenseManager
import java.io.IOException

/** One refresh lock for HTTP and socket handshakes; never bypasses operational login. */
object BackendSession {
    private var appContext: Context? = null
    fun configure(context: Context) { appContext = context.applicationContext }
    fun currentOwnerId(): String = appContext?.let { TadeuLicenseManager(it).currentUserId() }
        ?: throw IllegalStateException("Sessão autenticada necessária")
    @Synchronized
    fun token(): String {
        val context = appContext ?: throw IOException("Sessão não configurada")
        return try { TadeuLicenseManager(context).validAccessTokenBlocking() }
        catch (error: Exception) { throw IOException("Sessão expirada. Entre novamente.", error) }
    }
}
