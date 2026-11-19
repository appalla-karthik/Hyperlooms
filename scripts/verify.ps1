[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$workspace = Split-Path -Parent $PSScriptRoot

Write-Host "Verifying HYPER_LOOMS control plane..."
Push-Location (Join-Path $workspace "services/control-plane")
try {
    & .\gradlew.bat clean test
    if ($LASTEXITCODE -ne 0) { throw "Control-plane verification failed." }
}
finally {
    Pop-Location
}

Write-Host "Verifying HYPER_LOOMS web application..."
Push-Location (Join-Path $workspace "apps/web")
try {
    & pnpm check
    if ($LASTEXITCODE -ne 0) { throw "Web verification failed." }
    & pnpm build
    if ($LASTEXITCODE -ne 0) { throw "Web production build failed." }
}
finally {
    Pop-Location
}

$go = Get-Command go -ErrorAction SilentlyContinue
if ($null -ne $go) {
    Write-Host "Verifying HYPER_LOOMS provider agent..."
    Push-Location (Join-Path $workspace "agents/provider")
    try {
        $unformatted = & gofmt -l .
        if ($unformatted) {
            $unformatted | Write-Host
            throw "Provider-agent source is not gofmt formatted."
        }
        & go vet ./...
        if ($LASTEXITCODE -ne 0) { throw "Provider-agent vet failed." }
        & go test ./...
        if ($LASTEXITCODE -ne 0) { throw "Provider-agent tests failed." }
    }
    finally {
        Pop-Location
    }
}
else {
    Write-Warning "Go is not installed; provider-agent verification was skipped locally. CI still verifies it."
}

Write-Host "All available Phase 0 checks passed."
