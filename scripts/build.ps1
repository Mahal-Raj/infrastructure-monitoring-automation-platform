$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $projectRoot 'build\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\main\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
if (-not $sources) { throw 'No Java source files found.' }
& javac --release 17 --add-modules jdk.httpserver -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
Write-Output "Compiled controller classes to $classes"
