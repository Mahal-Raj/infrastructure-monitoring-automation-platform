$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'build.ps1')

$outputDirectory = Join-Path $projectRoot 'out'
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

$env:AGENT_BIND = '127.0.0.1'
$env:AGENT_PORT = '9100'
$env:NODE_ID = 'local'
$env:AGENT_TOKEN = 'demo-agent-token'
$env:AGENT_TOKEN_LOCAL = 'demo-agent-token'
$env:OPERATOR_TOKEN = 'demo-operator-token'
$env:NODE_CONFIG = Join-Path $projectRoot 'config\nodes.local.csv'
$env:PLATFORM_BIND = '127.0.0.1'
$env:PLATFORM_PORT = '8080'
$env:POLL_INTERVAL_SECONDS = '5'
$env:MANAGED_SERVICES = 'nginx'
$env:LOG_UNITS = 'nginx'
$env:ALLOW_MUTATIONS = 'false'

$agent = $null
$controller = $null
try {
    $agent = Start-Process python -ArgumentList @((Join-Path $projectRoot 'agent\infra_agent.py')) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $outputDirectory 'agent.stdout.log') -RedirectStandardError (Join-Path $outputDirectory 'agent.stderr.log')
    Start-Sleep -Milliseconds 800
    $controller = Start-Process java -ArgumentList @('--add-modules','jdk.httpserver','-cp',(Join-Path $projectRoot 'build\classes'),'com.sukhraj.infra.PlatformApplication') -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $outputDirectory 'controller.stdout.log') -RedirectStandardError (Join-Path $outputDirectory 'controller.stderr.log')
    Start-Sleep -Seconds 2
    Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/v1/nodes/local/collect' -Method Post | ConvertTo-Json -Depth 8
    Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/v1/nodes' | ConvertTo-Json -Depth 8
    Write-Output 'Demo is running at http://127.0.0.1:8080. Press Enter to stop it.'
    [void](Read-Host)
} finally {
    if ($controller -and -not $controller.HasExited) { Stop-Process -Id $controller.Id }
    if ($agent -and -not $agent.HasExited) { Stop-Process -Id $agent.Id }
}
