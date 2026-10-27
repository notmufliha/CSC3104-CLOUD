# down.ps1 - tears down the demo. By default only deletes the 'scamguard' namespace (keeps the minikube
# container running, so the next up.ps1 is fast). Pass -All to delete minikube itself too.
#
# Usage: .\demo\down.ps1 [-All]
param([switch]$All)
$Namespace = "scamguard"

if ($All) {
    Write-Host "Deleting namespace '$Namespace' and the minikube cluster..."
    kubectl delete namespace $Namespace --ignore-not-found --timeout=60s
    minikube delete
    Write-Host "Done. Next up.ps1 will create a fresh minikube cluster from scratch (slower)."
} else {
    Write-Host "Deleting namespace '$Namespace' (minikube itself stays running)..."
    kubectl delete namespace $Namespace --ignore-not-found --timeout=60s
    Write-Host "Done. Run .\demo\up.ps1 to bring it back (images are already built, so this will be fast)."
    Write-Host "Use '.\demo\down.ps1 -All' to also delete the minikube cluster."
}
