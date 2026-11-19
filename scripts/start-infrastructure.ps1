[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$workspace = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $workspace "infrastructure/compose/compose.yaml"

& docker compose -f $composeFile up -d
if ($LASTEXITCODE -ne 0) {
    throw "Unable to start local infrastructure. Confirm Docker Desktop/Engine is running."
}

& docker compose -f $composeFile ps
