# chaos.ps1 - kills one random pod of a chosen service and shows it recovering. Best run while
# demo\tools\load_sim.py is running in another terminal, so you can see in-flight requests survive.
#
# Usage: .\demo\chaos.ps1 [-Service impersonation-en]
param([string]$Service = "impersonation-en")
$Namespace = "scamguard"

$podsRaw = kubectl get pods -n $Namespace -l "app=$Service" -o jsonpath='{.items[*].metadata.name}'
if ([string]::IsNullOrWhiteSpace($podsRaw)) {
    Write-Host "No pods found for app=$Service in namespace $Namespace."
    Write-Host "Known services: redis, envoy-lb, aggregator, bert, gateway, urgency-en, money-en, impersonation-en, urgency-zh, money-zh, impersonation-zh"
    exit 1
}
$pods = $podsRaw -split ' '
$victim = $pods | Get-Random

Write-Host "Before:"
kubectl get pods -n $Namespace -l "app=$Service"
Write-Host ""
Write-Host ">>> Deleting pod: $victim"
kubectl delete pod -n $Namespace $victim

Write-Host ""
Write-Host "Watching it come back (Ctrl+C to stop watching -- the demo keeps running):"
kubectl get pods -n $Namespace -l "app=$Service" -w
