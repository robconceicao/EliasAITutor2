package com.roberto.eliasaitutor.model

import kotlinx.serialization.Serializable

@Serializable
data class FlashOffer(
    val title: String        = "",
    val description: String  = "",
    val discountPct: Int     = 50,
    val target: String       = "british_accent",
    val priceOriginal: Int   = 800,
    val priceFinal: Int      = 400,
    val offerDate: String    = "",
    val isFallback: Boolean  = false,
)

data class QuizQuestion(
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String,
)

data class UiChatBubble(
    val message: String,
    val isUser: Boolean,
    val vocabulary: List<String>   = emptyList(),
    val mistakes: List<MistakeEntry> = emptyList(),
    val sentiment: String          = "neutral",
    val sentimentCue: String       = "",
    val sentimentConfidence: Int   = 50,
)

data class MistakeEntry(
    val wrong: String = "",
    val right: String = "",
    val rule: String  = "",
    val raw: String   = "",
)

data class ParsedResponse(
    val response: String                   = "",
    val vocabulary: List<String>           = emptyList(),
    val mistakes: List<MistakeEntry>       = emptyList(),
    val sentimentDetected: String          = "neutral",
    val sentimentConfidence: Int           = 50,
    val sentimentCue: String               = "",
)
