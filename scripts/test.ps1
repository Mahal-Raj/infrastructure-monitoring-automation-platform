$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'build.ps1')

$mainClasses = Join-Path $projectRoot 'build\classes'
$testClasses = Join-Path $projectRoot 'build\test-classes'
New-Item -ItemType Directory -Force -Path $testClasses | Out-Null
$tests = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\test\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& javac --release 17 --add-modules jdk.httpserver -cp $mainClasses -d $testClasses $tests
if ($LASTEXITCODE -ne 0) { throw 'Java test compilation failed.' }
& java --add-modules jdk.httpserver -cp "$mainClasses;$testClasses" com.sukhraj.infra.FunctionalTest
if ($LASTEXITCODE -ne 0) { throw 'Java functional tests failed.' }

Push-Location (Join-Path $projectRoot 'agent')
try {
    & python -m unittest -v test_infra_agent.py
    if ($LASTEXITCODE -ne 0) { throw 'Python agent tests failed.' }
} finally {
    Pop-Location
}

Push-Location (Join-Path $projectRoot 'tools')
try {
    & python -m unittest -v test_export_report.py
    if ($LASTEXITCODE -ne 0) { throw 'Python report tests failed.' }
} finally {
    Pop-Location
}

& python -m py_compile (Join-Path $projectRoot 'agent\infra_agent.py') (Join-Path $projectRoot 'tools\export_report.py')
if ($LASTEXITCODE -ne 0) { throw 'Python syntax validation failed.' }
Write-Output 'PASS: Java, Python, and report-export validation completed.'
