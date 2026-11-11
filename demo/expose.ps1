# expose.ps1 - Workstream E: gives the phone ONE stable HTTPS address for the backend.
#
# The phone talks to the Ingress controller (ingress-nginx), never to a gateway pod. The Ingress follows
# the gateway Service, so deleting or restarting the gateway pod does not change the address and does not
# drop this forward (port-forward.ps1, by contrast, is pinned to one gateway pod).
#
# Why a port-forward and not `minikube tunnel`: with minikube's Docker driver on Windows/Mac, the tunnel
# only listens on the laptop's 127.0.0.1, which a phone on Wi-Fi cannot reach. This forward listens on
# every interface, so the phone uses https://<laptop Wi-Fi IP>:8443/api/. See demo/STABLE_ADDRESS.md.
#
# Each run also refreshes the HTTPS certificate if the laptop's IP changed (new Wi-Fi network). The CA
# stays the same, so the phone keeps trusting it.
#
# Usage: .\demo\expose.ps1   (leave it running in its own terminal during the demo)
# Windows Firewall, once, as Administrator, for a real phone:
#   New-NetFirewallRule -DisplayName "ScamGuard demo 8443" -Direction Inbound -LocalPort 8443 -Protocol TCP -Action Allow -Profile Private
$Port = 8443

$RepoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $RepoRoot
try {
    python demo/make_tls.py --apply
    if ($LASTEXITCODE -ne 0) { Write-Host "ERROR: could not refresh the certificate (is the cluster up? run .\demo\up.ps1)" -ForegroundColor Red; exit 1 }
} finally { Pop-Location }

# A phone connecting by IP sends no hostname (SNI), so nginx answers with its DEFAULT certificate. up.ps1 makes that
# the demo certificate (--default-ssl-certificate), but `minikube start` re-applies the ingress addon and drops the
# flag: then phones get "Kubernetes Ingress Controller Fake Certificate" and fail with "Trust anchor ... not found".
# So check it on every expose and put it back if it's gone.
$ctlArgs = kubectl get deployment ingress-nginx-controller -n ingress-nginx -o jsonpath='{.spec.template.spec.containers[0].args}'
if ($ctlArgs -notmatch 'default-ssl-certificate') {
    Write-Host "Ingress controller lost its default certificate (minikube restart?): restoring it..." -ForegroundColor Yellow
    $patch = '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--default-ssl-certificate=scamguard/gateway-tls"}]'
    $patchFile = Join-Path $env:TEMP "scamguard-ingress-patch.json"
    [IO.File]::WriteAllText($patchFile, $patch)   # a file, not an argument: PowerShell 5.1 mangles quotes passed to kubectl
    kubectl patch deployment ingress-nginx-controller -n ingress-nginx --type=json --patch-file $patchFile
    Remove-Item $patchFile
    kubectl rollout status deployment/ingress-nginx-controller -n ingress-nginx --timeout=180s
}

$laptopIp = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object {
    $_.InterfaceAlias -notmatch 'Loopback|vEthernet|WSL' -and $_.IPAddress -notlike '169.254.*'
} | Select-Object -First 1 -ExpandProperty IPAddress)
if (-not $laptopIp) { $laptopIp = "<run 'ipconfig': your Wi-Fi adapter's IPv4 address>" }

Write-Host ""
Write-Host "==============================================================================" -ForegroundColor Cyan
Write-Host "  Save ONE of these in the app's Settings (it stays valid across cluster" -ForegroundColor Cyan
Write-Host "  restarts and gateway pod restarts; only a new Wi-Fi network changes the IP):" -ForegroundColor Cyan
Write-Host "  EMULATOR   : https://10.0.2.2:${Port}/api/" -ForegroundColor Cyan
Write-Host "  REAL PHONE : https://${laptopIp}:${Port}/api/   (same Wi-Fi)" -ForegroundColor Cyan
Write-Host "  USB        : adb reverse tcp:${Port} tcp:${Port}   then https://127.0.0.1:${Port}/api/" -ForegroundColor Cyan
Write-Host "  First time only: install demo\tls\ca.crt on the device (demo\STABLE_ADDRESS.md)." -ForegroundColor Cyan
Write-Host "==============================================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Forwarding ${Port} -> ingress-nginx :443 (Ctrl+C to stop)..."

while ($true) {
    kubectl port-forward --address 0.0.0.0 -n ingress-nginx svc/ingress-nginx-controller "${Port}:443"
    Write-Host "forward dropped (ingress controller restarted?) -- restarting in 1s..."
    Start-Sleep -Seconds 1
}
