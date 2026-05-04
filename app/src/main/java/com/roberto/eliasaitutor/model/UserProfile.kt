package com.roberto.eliasaitutor.model

import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val userId: String        = "local_user",
    val xp: Int               = 0,
    val coins: Int            = 0,
    val level: Int            = 1,
    val messagesCount: Int    = 0,
    val streak: Int           = 0,
    val lastActiveDate: String= "",
    val streakFreezeCount: Int= 0,
    val britishUnlocked: Boolean = false,
    val confidence: Int       = 50,
    val clarity: Int          = 50,
    val posture: Int          = 50,
    val softSkillsSummary: String = "",
    val errorLog: List<ErrorEntry>       = emptyList(),
    val xpHistory: List<XpEntry>         = emptyList(),
    val sentimentHistory: List<SentimentEntry> = emptyList(),
    val unlockedScenarios: List<String>  = emptyList(),
)

@Serializable data class ErrorEntry(val timestamp: String, val error: String)
@Serializable data class XpEntry(val timestamp: String, val xp: Int)
@Serializable data class SentimentEntry(
    val timestamp: String,
    val detected: String,
    val confidence: Int,
    val cue: String,
)
