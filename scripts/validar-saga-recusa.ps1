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
$capture="teste.saga.$([guid]::NewGuid())"
$captureCreated=$false
$composeFile=Join-Path (Split-Path $PSScriptRoot -Parent) 'compose.integration.yaml'
function Queue($name) { Invoke-RestMethod "$rabbit/queues/%2F/$name" -Headers $rabbitHeaders -TimeoutSec 5 }
function Publish([string]$route,[string]$payload) {
    $r=Post "$rabbit/exchanges/%2F/mecanica.events/publish" @{properties=@{content_type='application/json';delivery_mode=2};routing_key=$route;payload=$payload;payload_encoding='string'} $rabbitHeaders
    if(-not $r.routed) { throw "Evento não roteado: $route" }
}
function Check-State {
    $r=Invoke-RestMethod -Headers $operatorHeaders "$execution/reparos/$($repair.id)"
    $s=Invoke-RestMethod "$os/ordens-servico/acompanhamento/$($order.id)"
    $p=Invoke-RestMethod -Headers $operatorHeaders "$execution/pecas/$($piece.id)"
    $reservation=Invoke-RestMethod -Headers $operatorHeaders "$execution/reparos/$($repair.id)/reserva"
    if($r.status -ne 'CANCELADO' -or $s.status -ne 'CANCELADA' -or $p.disponivel -ne 5 -or $reservation.status -ne 'LIBERADA') {
        throw 'Compensação incorreta: reparo/OS/reserva/estoque divergentes'
    }
    $charge=Invoke-WebRequest "$billing/cobrancas/$($budget.id)" -SkipHttpErrorCheck
    if($charge.StatusCode -ne 404) { throw 'Recusa não deveria gerar cobrança' }
}
try {
    $null=Invoke-RestMethod "$rabbit/queues/%2F/$capture" -Method Put -Headers $rabbitHeaders -ContentType 'application/json' -Body '{"durable":true,"auto_delete":false,"arguments":{"x-expires":600000}}'
    $captureCreated=$true
    foreach($route in @('orcamento.decidido.v1','orcamento.decidido.v2')) {
        $null=Post "$rabbit/bindings/%2F/e/mecanica.events/q/$capture" @{routing_key=$route;arguments=@{}} $rabbitHeaders
    }
    $order=Post "$os/ordens-servico" @{placa='ABC1D23';matriculaOperador=1} $headers
    $link=Await "$os/ordens-servico/$($order.id)/reparo" {param($r) $r.publicado} $headers
    $repair=Await "$execution/reparos/$($link.reparoId)" {param($r) $r.status -eq 'AGUARDANDO_APROVACAO'}
    if($repair.ordemId -ne $order.id) { throw 'Correlação OS/reparo inválida' }
    $piece=Post "$execution/pecas" @{sku="SAGA-$([guid]::NewGuid())";descricao='Filtro teste Saga';preco=25.50}
    $null=Post "$execution/pecas/$($piece.id)/entradas" @{movimentoId=[guid]::NewGuid().ToString();quantidade=5}
    $null=Post "$execution/reparos/$($repair.id)/diagnostico" @{observacoes='Saga de recusa';itens=@(@{tipo='PECA';referenciaId=$piece.id;quantidade=2})}
    $null=Post "$execution/reparos/$($repair.id)/reserva" @{itens=@(@{pecaId=$piece.id;quantidade=2})}
    $budget=Await "$billing/orcamentos/$(BudgetId $repair.id)" {param($r) $r.status -eq 'AGUARDANDO_APROVACAO'}
    $null=Await "$os/ordens-servico/acompanhamento/$($order.id)" {param($r) $r.status -eq 'AGUARDANDO_APROVACAO'}
    if((Invoke-RestMethod -Headers $operatorHeaders "$execution/pecas/$($piece.id)").disponivel -ne 3) { throw 'Reserva não descontou duas peças' }
    'PASS: OS real gerou reparo, orçamento e reserva; estoque disponível 5 -> 3'
    try {
        docker compose -f $composeFile stop rabbitmq
        if($LASTEXITCODE -ne 0) { throw 'Falha ao parar RabbitMQ local' }
        $refused=Invoke-RestMethod "$billing/orcamentos/$($budget.id)/recusar" -Method Post -TimeoutSec 15
        if($refused.status -ne 'RECUSADO') { throw 'Recusa não persistida com broker indisponível' }
        if((Invoke-RestMethod -Headers $operatorHeaders "$execution/reparos/$($repair.id)").status -ne 'AGUARDANDO_APROVACAO') { throw 'Estado inesperado durante indisponibilidade' }
        'PASS: Billing persistiu recusa durante indisponibilidade do RabbitMQ'
    } finally {
        docker compose -f $composeFile start rabbitmq
        if($LASTEXITCODE -ne 0) { throw 'Falha ao restaurar RabbitMQ local' }
    }
    $null=Await "$execution/reparos/$($repair.id)" {param($r) $r.status -eq 'CANCELADO'}
    $null=Await "$os/ordens-servico/acompanhamento/$($order.id)" {param($r) $r.status -eq 'CANCELADA'}
    Check-State
    'PASS: recuperação cancelou reparo/OS, liberou reserva e restaurou estoque 3 -> 5; nenhuma cobrança'
    $null=Await "$rabbit/queues/%2F/$capture" {param($r) [long]$r.messages_ready -ge 2} $rabbitHeaders
    $messages=Post "$rabbit/queues/%2F/$capture/get" @{count=100;ackmode='ack_requeue_false';encoding='auto';truncate=50000} $rabbitHeaders
    $events=@($messages | Where-Object { ($_.payload|ConvertFrom-Json).eventId -eq $budget.id })
    foreach($version in @(1,2)) {
        if(-not ($events|Where-Object {$_.routing_key -eq "orcamento.decidido.v$version"})) { throw "Evento v$version não capturado" }
    }
    # Let management statistics settle before establishing replay baselines.
    Start-Sleep -Seconds 6
    $beforeExecution=Queue 'execution.orcamento.v1'
    $beforeOs=Queue 'os.orcamento.v2'
    $deadBefore=[long](Queue 'execution.orcamento.v1.dlq').messages
    foreach($event in $events) { Publish $event.routing_key $event.payload }
    $null=Await "$rabbit/queues/%2F/execution.orcamento.v1" {param($r) [long]$r.message_stats.ack -gt [long]$beforeExecution.message_stats.ack} $rabbitHeaders
    $null=Await "$rabbit/queues/%2F/os.orcamento.v2" {param($r) [long]$r.message_stats.ack -gt [long]$beforeOs.message_stats.ack} $rabbitHeaders
    Check-State
    if([long](Queue 'execution.orcamento.v1.dlq').messages -ne $deadBefore) { throw 'Reentrega válida foi enviada para DLQ' }
    'PASS: reentrega dos envelopes originais v1/v2 não duplicou devolução nem alterou os estados'
    $conflict=($events|Where-Object {$_.routing_key -eq 'orcamento.decidido.v1'}|Select-Object -First 1).payload|ConvertFrom-Json
    $conflict.decisao='APROVADO'
    Publish 'orcamento.decidido.v1' ($conflict|ConvertTo-Json -Compress)
    $null=Await "$rabbit/queues/%2F/execution.orcamento.v1.dlq" {param($r) [long]$r.messages -gt $deadBefore} $rabbitHeaders
    Check-State
    'PASS: decisão conflitante isolada na DLQ sem reabrir reparo ou modificar estoque'
    $report=@{validadoEm=[DateTimeOffset]::UtcNow.ToString('o');ordemId=$order.id;reparoId=$repair.id;orcamentoId=$budget.id;pecaId=$piece.id;statusOs='CANCELADA';statusReparo='CANCELADO';reserva='LIBERADA';estoqueInicial=5;estoqueReservado=3;estoqueFinal=5;recuperacaoOutbox=$true;reentregaIdempotente=$true;conflitoNaDlq=$true;pagamentoRealValidado=$false}
    $report|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $state 'ultima-validacao-saga-recusa.json') -Encoding utf8
    $report|ConvertTo-Json
} finally {
    if($captureCreated) {
        # Remove only this run's temporary capture queue, never the business queues or DLQs.
        Invoke-RestMethod "$rabbit/queues/%2F/$capture" -Method Delete -Headers $rabbitHeaders -TimeoutSec 10 | Out-Null
    }
}
