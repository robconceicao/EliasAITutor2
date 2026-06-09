package com.roberto.eliasaitutor.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roberto.eliasaitutor.data.GameConstants
import com.roberto.eliasaitutor.model.FlashOffer
import com.roberto.eliasaitutor.viewmodel.EliasViewModel

private val Bg      = Color(0xFF0d0f14)
private val Surface = Color(0xFF161922)
private val Border  = Color(0xFF252a35)
private val Accent  = Color(0xFF4f8ef7)
private val Gold    = Color(0xFFf7c94f)
private val Green   = Color(0xFF3ecf8e)
private val Red     = Color(0xFFf76f6f)
private val Muted   = Color(0xFF7a8099)

@Composable
fun StoreScreen(vm: EliasViewModel) {
    val profile    by vm.profile.collectAsState()
    val flashOffer by vm.flashOffer.collectAsState()
    var toast      by remember { mutableStateOf<String?>(null) }

    val toastMsg by vm.toastMessage.collectAsState()
    LaunchedEffect(toastMsg) { if (toastMsg != null) { toast = toastMsg; vm.clearToast() } }

    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp)) {

        Text("🏪 Elias Store", color = Color(0xFFe8eaf0), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Balance: ${profile.coins} 🪙", color = Gold, fontSize = 14.sp)

        toast?.let {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Green.copy(alpha = 0.15f)),
                border = BorderStroke(1.dp, Green)) {
                Text(it, color = Green, modifier = Modifier.padding(10.dp), fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Flash Offer ────────────────────────────────────────────────────
        flashOffer?.let { offer ->
            FlashOfferCard(offer, profile.coins,
                onClaim = {
                    val ok = vm.buyBritishAccent(offer.priceFinal)
                    toast = if (ok) "✅ ${offer.title} claimed!" else "Need ${offer.priceFinal - profile.coins} more coins."
                })
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Border)
        Spacer(Modifier.height(16.dp))

        // ── British Accent ─────────────────────────────────────────────────
        Text("🎙️ Accent Shop", color = Color(0xFFe8eaf0), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        StoreCard(
            emoji = "🇬🇧", title = "British Accent",
            description = "Elias switches to a polished British RP accent for all responses. Perfect for UK employers.",
            price = GameConstants.BRITISH_COST, owned = profile.britishUnlocked, coins = profile.coins,
            onBuy = {
                val ok = vm.buyBritishAccent()
                toast = if (ok) "🇬🇧 British accent unlocked!" else "Need ${GameConstants.BRITISH_COST - profile.coins} more coins."
            }
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Border)
        Spacer(Modifier.height(16.dp))

        // ── Streak Freeze ──────────────────────────────────────────────────
        Text("🛡️ Streak Protection", color = Color(0xFFe8eaf0), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        StoreCard(
            emoji = "🛡️", title = "Streak Freeze",
            description = "Protects your daily streak if you miss a day. You own ${profile.streakFreezeCount} shield(s).",
            price = GameConstants.STREAK_FREEZE_COST, owned = false, coins = profile.coins,
            buyLabel = "Buy Shield",
            onBuy = {
                val ok = vm.buyStreakFreeze()
                toast = if (ok) "🛡️ Streak Freeze added!" else "Need ${GameConstants.STREAK_FREEZE_COST - profile.coins} more coins."
            }
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Border)
        Spacer(Modifier.height(16.dp))

        // ── Locked Scenarios ───────────────────────────────────────────────
        val lockedScenarios = GameConstants.SCENARIOS.filter { (name, data) ->
            profile.level < data.first && name !in profile.unlockedScenarios
        }
        if (lockedScenarios.isNotEmpty()) {
            Text("🔓 Scenario Early Access", color = Color(0xFFe8eaf0), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Unlock advanced scenarios before reaching the required level — ${GameConstants.EARLY_ACCESS_COST} 🪙 each.",
                color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            lockedScenarios.forEach { (name, data) ->
                ScenarioCard(name = name, minLevel = data.first, xpBonus = data.second,
                    coins = profile.coins, onUnlock = {
                        val ok = vm.buyScenarioAccess(name)
                        toast = if (ok) "✅ $name unlocked!" else "Need ${GameConstants.EARLY_ACCESS_COST - profile.coins} more coins."
                    })
                Spacer(Modifier.height(8.dp))
            }
            HorizontalDivider(color = Border)
            Spacer(Modifier.height(16.dp))
        }

        // ── Earn guide ─────────────────────────────────────────────────────
        Text("💡 How to Earn Coins", color = Color(0xFFe8eaf0), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        listOf(
            "💬 Send a chat message"         to "+${GameConstants.COINS_PER_MESSAGE} coins",
            "🎤 Complete shadowing round"    to "+${GameConstants.SHADOWING_COINS} coins",
            "🧠 Correct quiz answer"         to "+${GameConstants.QUIZ_COINS} coins · +${GameConstants.QUIZ_XP} XP",
            "📅 Daily streak login"          to "+${GameConstants.STREAK_BONUS_COINS} coins/day",
            "🌟 Reach Level 5"               to "+500 coins bonus",
            "🏆 Reach Level 10"              to "+1,500 coins bonus",
        ).forEach { (action, reward) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(action, color = Color(0xFFe8eaf0), fontSize = 13.sp)
                Text(reward, color = Green, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = Border)
        }
    }
}

@Composable
private fun FlashOfferCard(offer: FlashOffer, coins: Int, onClaim: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1a1330)),
        border = BorderStroke(1.dp, Color(0xFF6d28d9)), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚡ Flash Offer — Today Only!", color = Color(0xFFc084fc),
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (!offer.isFallback) {
                    Spacer(Modifier.width(6.dp))
                    Badge(containerColor = Color(0xFF6d28d9).copy(alpha = 0.4f)) {
                        Text("AI", color = Color(0xFFc084fc), fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(offer.title, color = Color(0xFFe8eaf0), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(offer.description, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${offer.priceOriginal} 🪙",
                    color = Muted, fontSize = 13.sp,
                    style = androidx.compose.ui.text.TextStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough))
                Text("${offer.priceFinal} 🪙", color = Gold, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Badge(containerColor = Gold.copy(alpha = 0.2f)) {
                    Text("${offer.discountPct}% OFF", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onClaim, enabled = coins >= offer.priceFinal,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6d28d9))) {
                Text("🛒 Claim for ${offer.priceFinal} 🪙")
            }
        }
    }
}

@Composable
private fun StoreCard(
    emoji: String, title: String, description: String,
    price: Int, owned: Boolean, coins: Int,
    buyLabel: String = "Buy",
    onBuy: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1a1e29)),
        border = BorderStroke(1.dp, Color(0xFF2d4070)), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 28.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Color(0xFFe8eaf0), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (owned) {
                        Spacer(Modifier.width(6.dp))
                        Badge(containerColor = Green.copy(alpha = 0.2f)) {
                            Text("OWNED", color = Green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(description, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(6.dp))
                Text("$price 🪙", color = Gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            if (!owned) {
                Button(onClick = onBuy, enabled = coins >= price,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(buyLabel, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ScenarioCard(name: String, minLevel: Int, xpBonus: Int, coins: Int, onUnlock: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1a1e29)),
        border = BorderStroke(1.dp, Color(0xFF2d4070)), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, color = Color(0xFFe8eaf0), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Requires Level $minLevel · +$xpBonus XP bonus", color = Muted, fontSize = 12.sp)
                Text("${GameConstants.EARLY_ACCESS_COST} 🪙", color = Gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Button(onClick = onUnlock, enabled = coins >= GameConstants.EARLY_ACCESS_COST,
                colors = ButtonDefaults.buttonColors(containerColor = Gold),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Text("🔓 Unlock", color = Bg, fontSize = 12.sp)
            }
        }
    }
}
