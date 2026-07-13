param(
    [ValidateSet("notification", "stop")]
    [string]$Source = "notification",
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$PayloadArguments
)

$ErrorActionPreference = "Stop"

$webhookUrl = $env:DISCORD_WEBHOOK_URL
if ([string]::IsNullOrWhiteSpace($webhookUrl)) {
    $webhookUrl = [Environment]::GetEnvironmentVariable("DISCORD_WEBHOOK_URL", "User")
}
if ([string]::IsNullOrWhiteSpace($webhookUrl)) {
    Write-Error "DISCORD_WEBHOOK_URL is not configured."
    exit 1
}

$rawPayload = ($PayloadArguments -join " ").Trim()
if ([string]::IsNullOrWhiteSpace($rawPayload) -and [Console]::IsInputRedirected) {
    $rawPayload = [Console]::In.ReadToEnd().Trim()
}

$payload = $null
if (-not [string]::IsNullOrWhiteSpace($rawPayload)) {
    try {
        $payload = $rawPayload | ConvertFrom-Json
    } catch {
        $payload = $null
    }
}

$eventName = if ($Source -eq "stop") { "Codex Stop" } else { "Codex Notification" }
$message = $null
if ($null -ne $payload) {
    if ($payload.type) {
        $eventName = "Codex $($payload.type)"
    } elseif ($payload.hook_event_name) {
        $eventName = "Codex $($payload.hook_event_name)"
    }

    if ($payload.'last-assistant-message') {
        $message = [string]$payload.'last-assistant-message'
    } elseif ($payload.last_assistant_message) {
        $message = [string]$payload.last_assistant_message
    } elseif ($payload.message) {
        $message = [string]$payload.message
    }
}

if ([string]::IsNullOrWhiteSpace($message)) {
    $message = if ($Source -eq "stop") {
        "Codex turn processing has stopped."
    } else {
        "Codex emitted a notification."
    }
}

$message = $message.Trim()
if ($message.Length -gt 1400) {
    $message = $message.Substring(0, 1397) + "..."
}

$body = @{
    username = "Codex"
    embeds = @(
        @{
            title = $eventName
            description = $message
            color = if ($Source -eq "stop") { 15158332 } else { 3447003 }
            fields = @(
                @{
                    name = "Working directory"
                    value = (Get-Location).Path
                    inline = $false
                }
            )
            timestamp = [DateTime]::UtcNow.ToString("o")
        }
    )
} | ConvertTo-Json -Depth 6

Invoke-RestMethod -Uri $webhookUrl -Method Post -ContentType "application/json; charset=utf-8" -Body $body | Out-Null
