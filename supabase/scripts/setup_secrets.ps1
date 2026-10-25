# ==============================================================================
# Script: setup_secrets.ps1
# Project: Run for Victory (By Ravi Kumar)
# Purpose: Configure Production Secrets in Supabase Vault via Supabase CLI
# ==============================================================================

param (
    [Parameter(Mandatory=$false)]
    [string]$EnvFile = "supabase/environments/production.env"
)

Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "RUN FOR VICTORY: PRODUCTION SECRETS VAULTING SETUP" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan

if (-not (Test-Path $EnvFile)) {
    Write-Warning "Production environment file not found at: $EnvFile"
    Write-Host "Please create $EnvFile using supabase/environments/production.env.example as reference."
    exit 1
}

Write-Host "Loading environment configurations from $EnvFile..." -ForegroundColor Yellow

# Parse environment variables safely
$secretsToSet = @()
Get-Content $EnvFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#")) {
        $keyVal = $line.Split("=", 2)
        if ($keyVal.Count -eq 2) {
            $key = $keyVal[0].Trim()
            $val = $keyVal[1].Trim()
            if ($key -in @("FIREBASE_SERVICE_ACCOUNT_KEY", "SUPABASE_SERVICE_ROLE_KEY", "APP_SIGNING_SHA256")) {
                $secretsToSet += "$key=$val"
            }
        }
    }
}

if ($secretsToSet.Count -eq 0) {
    Write-Warning "No vaulted secret keys found in $EnvFile."
    exit 0
}

Write-Host "Setting $($secretsToSet.Count) vaulted secrets in Supabase..." -ForegroundColor Green
foreach ($secret in $secretsToSet) {
    $keyName = $secret.Split("=")[0]
    Write-Host " -> Vaulting: $keyName"
}

Write-Host "`nTo commit these secrets to your live Supabase project, execute:" -ForegroundColor Yellow
Write-Host "supabase secrets set $($secretsToSet -join ' ')`n" -ForegroundColor White
Write-Host "Secrets setup script completed successfully." -ForegroundColor Green
