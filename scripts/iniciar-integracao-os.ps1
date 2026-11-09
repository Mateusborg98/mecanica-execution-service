#Requires -Version 7.3
# Local development only. Uses a dedicated RSA key protected by Windows DPAPI.
$ErrorActionPreference='Stop'
$state=Join-Path $env:LOCALAPPDATA 'MecanicaDevTools'
$null=New-Item -ItemType Directory -Path $state -Force
$keyPath=Join-Path $state 'os-integration-key.dpapi'
$envPath=Join-Path $state 'os-integration-public.env'
$rsa=[Security.Cryptography.RSA]::Create(2048)
try {
    if(Test-Path -LiteralPath $keyPath) {
        $private=[Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($keyPath),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
        $read=0; $rsa.ImportPkcs8PrivateKey($private,[ref]$read)
    } else {
        $private=$rsa.ExportPkcs8PrivateKey()
        [IO.File]::WriteAllBytes($keyPath,[Security.Cryptography.ProtectedData]::Protect($private,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser))
    }
    $public=$rsa.ExportSubjectPublicKeyInfoPem().Replace("`r",'').Replace("`n",'\n')
    [IO.File]::WriteAllText($envPath,"OS_INTEGRATION_PUBLIC_KEY='$public'`n")
} finally { if($private) { [Array]::Clear($private,0,$private.Length) }; $rsa.Dispose() }
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    docker compose --env-file $envPath -f compose.integration.yaml --profile os up -d --build --wait
    if($LASTEXITCODE -ne 0) { throw 'Falha ao iniciar integração' }
    'Integração iniciada: OS 18081, Billing 18082, Execution 18083. JWT local mantido; nenhuma credencial foi exibida.'
} finally { Pop-Location }
