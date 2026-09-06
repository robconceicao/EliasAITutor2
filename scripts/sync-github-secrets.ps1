<#
.SYNOPSIS
    Publica as chaves de local.properties como GitHub Actions secrets.

.DESCRIPTION
    O workflow build-release.yml monta local.properties no runner a partir de
    secrets do repositorio. Quando um secret nao existe, o campo correspondente
    de BuildConfig sai vazio na APK e o app compila sem falhar - Supabase nao
    conecta, o fallback de voz do cliente fica sem chave. O sintoma aparece so
    em runtime, no aparelho.

    Este script fecha essa lacuna lendo os valores direto de local.properties e
    enviando cada um por stdin para "gh secret set".

    Nenhum valor e impresso, gravado em arquivo temporario, passado como
    argumento de linha de comando ou exposto a um agente. Argumentos de linha de
    comando sao visiveis para outros processos e ficam no historico do shell;
    stdin nao. A saida diz apenas o nome de cada secret e se foi enviado.

.NOTES
    ENCODING: este arquivo e ASCII puro e gravado com BOM UTF-8, de proposito.
    O Windows PowerShell 5.1 le .ps1 sem BOM como Windows-1252. Um travessao
    UTF-8 (E2 80 94) vira tres bytes nessa leitura, e o ultimo (0x94) e a aspa
    dupla de fechamento em CP1252 - o parser a trata como delimitador de string
    e o script inteiro deixa de compilar. Nao introduza acentos nem travessoes
    aqui sem manter o BOM.

.EXAMPLE
    .\sync-github-secrets.ps1
    Envia todos os secrets de aplicacao encontrados.

.EXAMPLE
    .\sync-github-secrets.ps1 -Only SUPABASE_URL,SUPABASE_KEY,ELEVENLABS_API_KEY
    Envia apenas os tres nomeados.

.EXAMPLE
    .\sync-github-secrets.ps1 -WhatIf
    Mostra o que seria enviado, sem enviar nada.
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string] $Repo = 'robconceicao/EliasAITutor2',

    [string] $PropertiesPath = (Join-Path $PSScriptRoot '..\local.properties'),

    # Subconjunto a enviar. Vazio = todos os nomes conhecidos abaixo.
    [string[]] $Only = @()
)

$ErrorActionPreference = 'Stop'

# Nomes lidos por .github/workflows/build-release.yml ao montar local.properties.
# Os secrets de assinatura (KEYSTORE_*, KEY_*) ficam de fora de proposito: a
# senha do keystore nao mora em local.properties e o .jks vai como base64 por
# outro caminho.
$known = @(
    'CLAUDE_API_KEY'
    'DEEPSEEK_API_KEY'
    'OPENAI_API_KEY'
    'ELEVENLABS_API_KEY'
    'ELEVENLABS_VOICE_ID'
    'SUPABASE_URL'
    'SUPABASE_KEY'
    'GEMINI_API_KEY'
    'GROQ_API_KEY'
    'CARTESIA_API_KEY'
    'BACKEND_URL'
)

if (-not (Test-Path -LiteralPath $PropertiesPath)) {
    throw "local.properties nao encontrado em: $PropertiesPath"
}

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw 'gh CLI nao encontrado no PATH. Instale em https://cli.github.com/'
}

$wanted = if ($Only.Count -gt 0) { $Only } else { $known }

$unknown = $wanted | Where-Object { $_ -notin $known }
if ($unknown) {
    throw "Nome(s) fora da lista conhecida: $($unknown -join ', ')"
}

# Parse manual em vez de ConvertFrom-StringData: valores como URLs e JWTs contem
# '=', entao so o primeiro separador conta.
$props = @{}
$resolved = (Resolve-Path -LiteralPath $PropertiesPath).Path
foreach ($line in [System.IO.File]::ReadAllLines($resolved)) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0) { continue }
    if ($trimmed.StartsWith('#') -or $trimmed.StartsWith('!')) { continue }
    $sep = $trimmed.IndexOf('=')
    if ($sep -lt 1) { continue }
    $name = $trimmed.Substring(0, $sep).Trim()
    $value = $trimmed.Substring($sep + 1).Trim()
    $props[$name] = $value
}

$sent = 0
$skipped = @()

foreach ($name in $wanted) {
    $value = $props[$name]

    if ([string]::IsNullOrWhiteSpace($value)) {
        Write-Host ("  {0,-22} ausente ou vazio em local.properties, pulado" -f $name) -ForegroundColor DarkGray
        $skipped += $name
        continue
    }

    if (-not $PSCmdlet.ShouldProcess("$Repo/$name", 'gh secret set')) { continue }

    # Por stdin: o valor nunca vira argumento visivel a outros processos.
    # O pipe do PowerShell acrescenta uma quebra de linha no fim; o passo
    # "Normalizar segredos" do workflow remove CR/LF antes de qualquer consumo,
    # entao o outro lado esta coberto.
    $value | & gh secret set $name --repo $Repo

    if ($LASTEXITCODE -ne 0) {
        throw "gh secret set falhou para $name (exit $LASTEXITCODE)"
    }

    Write-Host ("  {0,-22} enviado" -f $name) -ForegroundColor Green
    $sent++
}

# Remove a copia em memoria assim que deixa de ser necessaria.
$props.Clear()

Write-Host ''
Write-Host "$sent secret(s) enviado(s) para $Repo." -ForegroundColor Cyan
if ($skipped.Count -gt 0) {
    Write-Host "Nao enviados (ausentes em local.properties): $($skipped -join ', ')" -ForegroundColor Yellow
}
Write-Host "Confira com: gh secret list --repo $Repo" -ForegroundColor DarkGray
