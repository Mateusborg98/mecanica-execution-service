#Requires -Version 7.3
# Executes only against the local integration environment; never prints the JWT.
$ErrorActionPreference='Stop'
$os='http://127.0.0.1:18081';$execution='http://127.0.0.1:18083';$billing='http://127.0.0.1:18082'
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
$operatorHeaders=$headers
function Post($url,$body,$auth=@{}) { if($url.StartsWith($execution+'/')){$auth=$operatorHeaders}; Invoke-RestMethod $url -Method Post -Headers $auth -ContentType 'application/json' -Body ($body|ConvertTo-Json -Depth 10) -TimeoutSec 10 }
function Await($url,$predicate,$auth=@{}) {
    if($url.StartsWith($execution+'/')){$auth=$operatorHeaders}
    for($n=0;$n -lt 90;$n++) {
        try { $result=Invoke-RestMethod $url -Headers $auth -TimeoutSec 3;if(& $predicate $result) { return $result } }
        catch { if($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -notin @(404,503)) { throw } }
        Start-Sleep -Seconds 1
    }
    throw "Timeout aguardando $url"
}
function BudgetId($id) {
    $hash=[Security.Cryptography.MD5]::HashData([Text.Encoding]::UTF8.GetBytes("mecanica:diagnostico:v1:$id"))
    $hash[6]=($hash[6] -band 15) -bor 48;$hash[8]=($hash[8] -band 63) -bor 128
    $h=[Convert]::ToHexString($hash).ToLowerInvariant()
    "$($h.Substring(0,8))-$($h.Substring(8,4))-$($h.Substring(12,4))-$($h.Substring(16,4))-$($h.Substring(20,12))"
}
foreach($url in @($os,$execution,$billing)) { $null=Await "$url/actuator/health" {param($r) $r.status -eq 'UP'} }
$rabbit='http://127.0.0.1:15673/api'
$rabbitHeaders=@{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('mecanica_local:mecanica_local_password'))}
$queue='os.reparo-cancelado.v1';$source="$queue.dlq"
$cli=Join-Path $PSScriptRoot '..\tools\dlq\target\mecanica-dlq.jar'
if(-not (Test-Path $cli)) { throw 'Compile a ferramenta Java conforme docs/reprocessamento-dlq.md' }
$initial=Invoke-RestMethod "$rabbit/queues/%2F/$source" -Headers $rabbitHeaders
if([long]$initial.messages -ne 0 -or [long]$initial.messages_unacknowledged -ne 0) { throw 'DLQ de cancelamento da OS precisa estar vazia para este teste; nenhuma mensagem existente será removida.' }
$capture="teste.dlq.$([guid]::NewGuid())";$created=$false
try {
    $null=Invoke-RestMethod "$rabbit/queues/%2F/$capture" -Method Put -Headers $rabbitHeaders -ContentType 'application/json' -Body '{"durable":true,"auto_delete":false,"arguments":{"x-expires":600000}}'
    $created=$true
    $null=Post "$rabbit/bindings/%2F/e/mecanica.events/q/$capture" @{routing_key='reparo.cancelado.v1';arguments=@{}} $rabbitHeaders
    $order=Post "$os/ordens-servico" @{placa='ABC1D23';matriculaOperador=1} $headers
    $link=Await "$os/ordens-servico/$($order.id)/reparo" {param($r) $r.publicado} $headers
    $repair=Await "$execution/reparos/$($link.reparoId)" {param($r) $r.status -eq 'AGUARDANDO_APROVACAO'}
    $null=Invoke-RestMethod -Headers $operatorHeaders "$execution/reparos/$($repair.id)/cancelar" -Method Post
    $null=Await "$os/ordens-servico/acompanhamento/$($order.id)" {param($r) $r.status -eq 'CANCELADA'}
    $null=Await "$rabbit/queues/%2F/$capture" {param($r) [long]$r.messages_ready -ge 1} $rabbitHeaders
    $messages=Post "$rabbit/queues/%2F/$capture/get" @{count=100;ackmode='ack_requeue_false';encoding='auto';truncate=50000} $rabbitHeaders
    $event=$messages|Where-Object {($_.payload|ConvertFrom-Json).reparoId -eq $repair.id}|Select-Object -First 1
    if(-not $event) { throw 'Cancelamento original não capturado' }
    # A valid event is placed in the DLQ to simulate a transient failure already corrected.
    $routed=Post "$rabbit/exchanges/%2F/amq.default/publish" @{properties=@{content_type='application/json';delivery_mode=2;message_id=$repair.id};routing_key=$source;payload=$event.payload;payload_encoding='string'} $rabbitHeaders
    if(-not $routed.routed) { throw 'Simulação não roteada' }
    $null=Await "$rabbit/queues/%2F/$source" {param($r) [long]$r.messages_ready -eq 1} $rabbitHeaders
    $inspection=& java -jar $cli inspect --local --queue $source
    if($LASTEXITCODE -ne 0) { throw 'Inspeção da DLQ falhou' }
    $selected=$inspection|ConvertFrom-Json
    if($selected.eventId -ne $repair.id -or -not $selected.envelopeValid) { throw 'Evento selecionado incorreto' }
    $null=Await "$rabbit/queues/%2F/$source" {param($r) [long]$r.messages_ready -eq 1} $rabbitHeaders
    'PASS: inspeção identificou evento/hash sem removê-lo da DLQ'
    Start-Sleep -Seconds 6
    $before=Invoke-RestMethod "$rabbit/queues/%2F/$queue" -Headers $rabbitHeaders
    $output=& java -jar $cli replay --local --queue $source --sha256 $selected.sha256 --operator validacao-local --reason 'Falha transitória simulada; consumidor saudável e evento correlacionado à OS de teste' --classification transient --cause-fixed
    if($LASTEXITCODE -ne 0) { throw 'Reprocessamento falhou' }
    $result=$output|ConvertFrom-Json
    if($result.state -ne 'ACK_SENT' -or $result.target -ne $queue) { throw 'Resultado de reprocessamento inesperado' }
    $null=Await "$rabbit/queues/%2F/$queue" {param($r) [long]$r.message_stats.ack -gt [long]$before.message_stats.ack} $rabbitHeaders
    $null=Await "$rabbit/queues/%2F/$source" {param($r) [long]$r.messages -eq 0 -and [long]$r.messages_unacknowledged -eq 0} $rabbitHeaders
    if((Invoke-RestMethod "$os/ordens-servico/acompanhamento/$($order.id)").status -ne 'CANCELADA') { throw 'Reprocessamento alterou resultado de negócio' }
    'PASS: mensagem reenviada diretamente à OS, confirmada e retirada da DLQ; consumidor preservou CANCELADA'
    $history=& java -jar $cli history --local
    if($LASTEXITCODE -ne 0) { throw 'Consulta de auditoria falhou' }
    $entry=@($history|ConvertFrom-Json)|Where-Object {$_.id -eq $result.attemptId}|Select-Object -First 1
    if(-not $entry -or $entry.state -ne 'ACK_SENT' -or $entry.sha -ne $selected.sha256 -or $entry.operator -ne 'validacao-local') { throw 'Auditoria incompleta' }
    'PASS: auditoria persistiu responsável, motivo, hash e estado da tentativa'
    $report=@{validadoEm=[DateTimeOffset]::UtcNow.ToString('o');ordemId=$order.id;reparoId=$repair.id;attemptId=$result.attemptId;sha256=$selected.sha256;dlq=$source;destino=$queue;statusOs='CANCELADA';falhaTransitoriaSimulada=$true;consumoRealConfirmado=$true}
    $report|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $state 'ultima-validacao-dlq.json') -Encoding utf8
    $report|ConvertTo-Json
} finally {
    if($created) { Invoke-RestMethod "$rabbit/queues/%2F/$capture" -Method Delete -Headers $rabbitHeaders -TimeoutSec 10|Out-Null }
}
