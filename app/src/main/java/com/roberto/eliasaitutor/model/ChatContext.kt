package com.roberto.eliasaitutor.model

/**
 * Separação oficial de fluxos de conversa (Task Final v1.0).
 *
 * PROGRAM — vindo da tela Programa: prompt da semana, sem seletor de nível,
 *           TTS streaming automático via backend.
 * FREE    — aba Chat: fluxo aberto, pode perguntar nível.
 */
enum class ChatType {
    PROGRAM,
    FREE,
}

data class ChatContext(
    val type: ChatType,
    val week: Int? = null,
    val title: String = "",
    val lexis: String = "",
    val grammar: String = "",
    val phase: Int = 1,
    val sessionType: String = "themed",
)
