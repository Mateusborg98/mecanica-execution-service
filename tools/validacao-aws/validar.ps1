#Requires -Version 7.3
$ErrorActionPreference='Stop'
$context='arn:aws:eks:us-east-1:533267079174:cluster/mecanica-fase4'
$kubectl=Join-Path $env:LOCALAPPDATA 'MecanicaDevTools\kubernetes-1.36\kubectl.exe'
$processes=@()
function Base64Url([byte[]]$bytes) { [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_') }
Push-Location $PSScriptRoot
try {
    New-Item -ItemType Directory -Path target -Force | Out-Null
    & '..\..\mvnw.cmd' -B -ntp compile dependency:build-classpath '-Dmdep.outputFile=target/classpath.txt'
    if($LASTEXITCODE -ne 0){throw 'Compilacao do validador falhou.'}
    foreach($entry in @(@{service='os';local=19081;remote=8081},@{service='billing';local=19082;remote=8082},@{service='execution';local=19083;remote=8083})) {
        if(Get-NetTCPConnection -LocalPort $entry.local -State Listen -ErrorAction SilentlyContinue){throw "Porta $($entry.local) ocupada; nao reutilizar destino desconhecido."}
        $argsList=@('--context',$context,'-n','mecanica-fase4','port-forward',"service/mecanica-$($entry.service)-service","$($entry.local):$($entry.remote)",'--address','127.0.0.1')
        $process=Start-Process -FilePath $kubectl -ArgumentList $argsList -WindowStyle Hidden -PassThru -RedirectStandardOutput "target/forward-$($entry.service).log" -RedirectStandardError "target/forward-$($entry.service)-error.log"
        $processes += $process
        $ready=$false
        for($i=0;$i -lt 30;$i++) {
            if($process.HasExited){throw 'Port-forward encerrou antes da validacao.'}
            try { if((Invoke-RestMethod "http://127.0.0.1:$($entry.local)/actuator/health/readiness" -TimeoutSec 2).status -eq 'UP'){$ready=$true;break} } catch {}
            Start-Sleep -Seconds 1
        }
        if(-not $ready){throw 'API nao ficou pronta pelo port-forward.'}
    }
    $rsa=[Security.Cryptography.RSA]::Create()
    try {
        $private=[Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes((Join-Path $env:LOCALAPPDATA 'MecanicaDevTools\os-integration-key.dpapi')),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
        $read=0;$rsa.ImportPkcs8PrivateKey($private,[ref]$read)
        $now=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
        $header=Base64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"RS256","typ":"JWT"}'))
        $claims=@{iss='mecanica-auth';sub='10000000-0000-0000-0000-000000000001';role='ADMINISTRADOR';iat=$now;exp=($now+900)}|ConvertTo-Json -Compress
        $payload=Base64Url ([Text.Encoding]::UTF8.GetBytes($claims))
        $unsigned="$header.$payload"
        $signature=Base64Url ($rsa.SignData([Text.Encoding]::UTF8.GetBytes($unsigned),[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1))
        $classpath='target/classes;'+(Get-Content target/classpath.txt -Raw).Trim()
        "$unsigned.$signature" | & java -cp $classpath br.com.techchallenge.validacao.ValidacaoAws --confirmar-laboratorio
        if($LASTEXITCODE -ne 0){throw 'Validacao Java falhou. Os registros ficticios criados foram preservados para diagnostico.'}
    } finally {
        if($private){[Array]::Clear($private,0,$private.Length)}
        $rsa.Dispose();$unsigned=$null;$signature=$null
    }
} finally {
    foreach($process in $processes){if(-not $process.HasExited){$process.Kill();$process.WaitForExit()}}
    Pop-Location
}
