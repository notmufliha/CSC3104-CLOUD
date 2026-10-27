# spread.ps1 - shows how many requests each pod handled (via Envoy admin stats) + kubectl get hpa,pods.
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
python "$ScriptDir\tools\spread_report.py"
