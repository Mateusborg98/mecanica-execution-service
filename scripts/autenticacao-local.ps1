#Requires -Version 7.3
# Local operator token, generated from the existing DPAPI-protected development key.
function Get-LocalOperatorHeaders {
    $state=Join-Path $env:LOCALAPPDATA 'MecanicaDevTools'
function Base64Url([byte[]]$bytes) { [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_') }
$rsa=[Security.Cryptography.RSA]::Create()
try {
    $private=[Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes((Join-Path $state 'os-integration-key.dpapi')),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
    $read=0;$rsa.ImportPkcs8PrivateKey($private,[ref]$read)
    $now=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $header=Base64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"RS256","typ":"JWT"}'))
    $claims=@{iss='mecanica-integration-local';sub='10000000-0000-0000-0000-000000000001';role='OPERADOR';iat=$now;exp=($now+900)}|ConvertTo-Json -Compress
    $payload=Base64Url ([Text.Encoding]::UTF8.GetBytes($claims))
    $unsigned="$header.$payload"
    $signature=Base64Url ($rsa.SignData([Text.Encoding]::UTF8.GetBytes($unsigned),[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1))
    $headers=@{Authorization="Bearer $unsigned.$signature"}
} finally { if($private) { [Array]::Clear($private,0,$private.Length) };$rsa.Dispose() }

    return $headers
}
