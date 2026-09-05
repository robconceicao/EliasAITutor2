package com.roberto.eliasaitutor.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roberto.eliasaitutor.data.TadeuLicense
import com.roberto.eliasaitutor.data.TadeuLicenseManager
import kotlinx.coroutines.launch

@Composable
fun TadeuLicenseGate(
    manager: TadeuLicenseManager,
    content: @Composable (TadeuLicense?) -> Unit,
) {
    if (!manager.configured) {
        // Transição de homologação: o build atual não é bloqueado até as variáveis
        // públicas da Tadeu Apps serem configuradas no CI/local.properties.
        content(null)
        return
    }

    var license by remember { mutableStateOf<TadeuLicense?>(null) }
    var loading by remember { mutableStateOf(true) }
    var needsLogin by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            license = manager.restoreAndFetch()
        } catch (_: Exception) {
            needsLogin = true
        } finally {
            loading = false
        }
    }

    when {
        license != null -> content(license)
        loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Validando licença Tadeu Apps…", color = Color(0xFF7A8099))
            }
        }
        needsLogin -> Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(modifier = Modifier.widthIn(max = 480.dp)) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("TADEU APPS", color = Color(0xFF4F8EF7), fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Ativar licença do Elias AI Tutor", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Use a mesma conta da Tadeu Apps em que você ativou Gratuito, Pro ou Premium.",
                        color = Color(0xFF7A8099),
                    )
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("E-mail Tadeu Apps") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Senha Tadeu Apps") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (message != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(message!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            if (email.isBlank() || password.isBlank()) {
                                message = "Informe e-mail e senha."
                                return@Button
                            }
                            loading = true
                            message = null
                            scope.launch {
                                try {
                                    license = manager.signIn(email, password)
                                    needsLogin = false
                                } catch (error: Exception) {
                                    message = if (error.message?.contains("TADEU_LICENSE_DENIED") == true) {
                                        "Sua conta não possui assinatura ativa do Elias AI Tutor. Ative Gratuito, Pro ou Premium na Tadeu Apps."
                                    } else {
                                        "Não foi possível validar a licença. Confira os dados e tente novamente."
                                    }
                                } finally {
                                    loading = false
                                }
                            }
                        },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (loading) "VALIDANDO…" else "VALIDAR LICENÇA")
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Após uma validação online, a licença pode ser reutilizada por até 24 horas sem conexão.",
                        color = Color(0xFF7A8099),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
