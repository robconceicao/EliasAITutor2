package com.roberto.eliasaitutor.network

import com.roberto.eliasaitutor.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.serialization.Serializable

// 1. Definição do modelo para o Supabase (deve bater com a sua tabela)
@Serializable
data class ChatMessage(
    val user_id: String,
    val message: String,
    val is_user: Boolean
)

// 2. O Motor de Conexão
object SupabaseManager {
    val client = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_KEY
    ) {
        install(Postgrest)
    }
}

