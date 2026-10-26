# phone_check.ps1 - checks the gateway is reachable the way a real phone on the same Wi-Fi would reach
# it: by the laptop's Wi-Fi IP, not localhost. Run this AFTER demo\port-forward.ps1 is running in another
# terminal (or after `docker compose up`, which also listens on all interfaces).
#
# Usage: .\demo\tools\phone_check.ps1 [-Ip 192.168.1.23]   (auto-detects the IP if not given)
param([string]$Ip = "")

if (-not $Ip) {
    $Ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object {
        $_.InterfaceAlias -notmatch 'Loopback|vEthernet|WSL' -and $_.IPAddress -notlike '169.254.*'
    } | Select-Object -First 1 -ExpandProperty IPAddress)
}
if (-not $Ip) {
    Write-Host "Could not auto-detect your Wi-Fi IP. Run 'ipconfig' and pass it: .\demo\tools\phone_check.ps1 -Ip 192.168.1.23"
    exit 1
}

$Url = "http://${Ip}:8080"
Write-Host "Checking gateway at $Url (this is the address you'd type into the app's Settings)..."
Write-Host ""

Write-Host "--- GET $Url/healthz ---"
try {
    $resp = Invoke-RestMethod -Uri "$Url/healthz" -TimeoutSec 5
    $resp | ConvertTo-Json
} catch {
    Write-Host "FAILED: $_"
    Write-Host "Checklist:"
    Write-Host "  1. Is '.\demo\port-forward.ps1' (or docker compose) running?"
    Write-Host "  2. Allow inbound 8080 on Private networks (run as Administrator):"
    Write-Host "       New-NetFirewallRule -DisplayName 'ScamGuard demo 8080' -Direction Inbound -LocalPort 8080 -Protocol TCP -Action Allow -Profile Private"
    Write-Host "  3. Phone and laptop on the SAME Wi-Fi (venue Wi-Fi often blocks this -- use a phone hotspot instead)."
    Write-Host "  4. USB alternative: adb reverse tcp:8080 tcp:8080, then use http://localhost:8080/ in the app."
    exit 1
}
Write-Host ""

Write-Host "--- POST $Url/v1/utterance (one sample caller line) ---"
$body = @{ call_id = "phone-check"; seq = 1; speaker = "caller"; lang = "en"; text = "This is the police. Transfer the money to the safe account now and read me the OTP." } | ConvertTo-Json
$verdict = Invoke-RestMethod -Uri "$Url/v1/utterance" -Method Post -ContentType "application/json" -Body $body -TimeoutSec 5
$verdict | ConvertTo-Json
Write-Host ""
Write-Host "If you saw a verdict above, the phone/emulator will work with this URL: $Url/"
