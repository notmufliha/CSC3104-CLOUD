# watch.ps1 - live view for the presentation: pods + HPA state, refreshing. Ctrl+C to stop.
$Namespace = "scamguard"
kubectl get pods,hpa -n $Namespace -w
