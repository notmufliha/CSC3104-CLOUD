# watch.ps1 - live view for the presentation: pods + HPA state, refreshing every 2s. Ctrl+C to stop.
#
# Not `kubectl get pods,hpa -n scamguard -w`: kubectl's --watch only supports ONE resource type at a time
# on several kubectl versions (this repo's: "error: you may only specify a single resource type") --
# found for real while testing this script. A clear-and-reprint loop works everywhere and shows both.
$Namespace = "scamguard"

while ($true) {
    Clear-Host
    Write-Host "ScamGuard demo -- namespace: $Namespace -- $(Get-Date -Format 'HH:mm:ss') (Ctrl+C to stop)"
    Write-Host ""
    kubectl get pods,hpa -n $Namespace
    Start-Sleep -Seconds 2
}
