# port-forward.ps1 - exposes the in-cluster gateway on localhost:8080 (and your Wi-Fi IP) so the Android
# app (emulator or real phone) can reach it.
#
# IMPORTANT: kubectl port-forward is tied to ONE gateway pod. If that pod restarts (chaos.ps1 kills it, a
# crash, a rollout), the forward dies silently. This script loops and restarts it automatically. If the
# app suddenly can't connect, check this terminal: if it shows "forward dropped, restarting", the gateway
# pod itself is the problem (check `kubectl get pods -n scamguard -l app=gateway`), not the port-forward.
#
# Usage: .\demo\port-forward.ps1   (leave it running in its own terminal during the demo)
# NOTE: Windows Firewall may prompt to allow inbound connections on first run -- allow it on Private
# networks for a real phone to reach you. PowerShell to do this ahead of time (run as Administrator):
#   New-NetFirewallRule -DisplayName "ScamGuard demo 8080" -Direction Inbound -LocalPort 8080 -Protocol TCP -Action Allow -Profile Private
$Namespace = "scamguard"

$laptopIp = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object {
    $_.InterfaceAlias -notmatch 'Loopback|vEthernet|WSL' -and $_.IPAddress -notlike '169.254.*'
} | Select-Object -First 1 -ExpandProperty IPAddress)
if (-not $laptopIp) { $laptopIp = "<could not detect -- run 'ipconfig' and look for your Wi-Fi adapter's IPv4 address>" }

Write-Host ""
Write-Host "==============================================================================" -ForegroundColor Cyan
Write-Host "  EMULATOR URL      : http://10.0.2.2:8080/" -ForegroundColor Cyan
Write-Host "  REAL PHONE URL    : http://${laptopIp}:8080/   (phone must be on the same Wi-Fi)" -ForegroundColor Cyan
Write-Host "  USB ALTERNATIVE   : adb reverse tcp:8080 tcp:8080   then use http://localhost:8080/ in the app" -ForegroundColor Cyan
Write-Host "==============================================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Starting port-forward (Ctrl+C to stop)..."

while ($true) {
    kubectl port-forward --address 0.0.0.0 -n $Namespace svc/gateway 8080:8080
    Write-Host "forward dropped (gateway pod likely restarted) -- restarting in 1s..."
    Start-Sleep -Seconds 1
}
