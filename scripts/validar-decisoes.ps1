# Run only against the isolated compose.integration.yaml environment.
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
$ErrorActionPreference = 'Stop'
$composeArgs=@('-f','compose.integration.yaml')
$execution='http://127.0.0.1:18083'
$billing='http://127.0.0.1:18082'
$rabbit='http://127.0.0.1:15673/api'
$auth=[Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('mecanica_local:mecanica_local_password'))
$headers=@{Authorization="Basic $auth"}
function Wait-Health($url) {
    for ($n=0;$n -lt 30;$n++) {
        try { if ((Invoke-RestMethod "$url/actuator/health" -TimeoutSec 2).status -eq 'UP') { return } } catch {}
        Start-Sleep -Seconds 1
    }
    throw "Health failed: $url"
}
function Post-Json($url,$body) { Invoke-RestMethod -Method Post -Uri $url -ContentType 'application/json' -Body ($body|ConvertTo-Json -Depth 10) -TimeoutSec 10 }
function Budget-Id($reparo) {
    $bytes=[Text.Encoding]::UTF8.GetBytes("mecanica:diagnostico:v1:$reparo")
    $md5=[Security.Cryptography.MD5]::Create()
    try { $hash=$md5.ComputeHash($bytes) } finally { $md5.Dispose() }
    $hash[6]=($hash[6] -band 15) -bor 48
    $hash[8]=($hash[8] -band 63) -bor 128
    $hex=([BitConverter]::ToString($hash)).Replace('-','').ToLowerInvariant()
    return "$($hex.Substring(0,8))-$($hex.Substring(8,4))-$($hex.Substring(12,4))-$($hex.Substring(16,4))-$($hex.Substring(20,12))"
}
function Wait-Budget($id) {
    for($n=0;$n -lt 60;$n++) {
        try { return Invoke-RestMethod "$billing/orcamentos/$id" -TimeoutSec 2 } catch {}
        Start-Sleep -Seconds 1
    }
    throw "Budget not received: $id"
}
function Queue-State($name) { Invoke-RestMethod "$rabbit/queues/%2F/$name" -Headers $headers -TimeoutSec 5 }
function Publish($payload, $route='orcamento.decidido.v1') {
    $request=@{properties=@{content_type='application/json'; delivery_mode=2};routing_key=$route;payload=$payload;payload_encoding='string'}
    $response=Invoke-RestMethod "$rabbit/exchanges/%2F/mecanica.events/publish" -Method Post -Headers $headers -ContentType 'application/json' -Body ($request|ConvertTo-Json -Depth 10)
    if (-not $response.routed) { throw 'Message not routed' }
}

function Wait-Status($id,$expected) {
    for($n=0;$n -lt 75;$n++) {
        $state=Invoke-RestMethod "$execution/reparos/$id" -TimeoutSec 5
        if($state.status -eq $expected) { return $state }
        Start-Sleep -Seconds 1
    }
    throw "Reparo $id permaneceu $($state.status), esperado $expected"
}
function New-Scenario {
    $tag=[guid]::NewGuid().ToString()
    $piece=Post-Json "$execution/pecas" @{sku="DECISAO-$tag";descricao='Filtro';preco=25.50}
    $null=Post-Json "$execution/pecas/$($piece.id)/entradas" @{movimentoId=[guid]::NewGuid().ToString();quantidade=5}
    $repair=Post-Json "$execution/reparos" @{ordemId=[guid]::NewGuid().ToString()}
    $null=Post-Json "$execution/reparos/$($repair.id)/diagnostico" @{observacoes='Teste decisao';itens=@(@{tipo='PECA';referenciaId=$piece.id;quantidade=2})}
    $null=Post-Json "$execution/reparos/$($repair.id)/reserva" @{itens=@(@{pecaId=$piece.id;quantidade=2})}
    $budget=Wait-Budget (Budget-Id $repair.id)
    return @{piece=$piece;repair=$repair;budget=$budget}
}
function Repeat-Event($scenario,$decision) {
    $event=@{schemaVersion=1;eventType='OrcamentoDecidido';eventId=$scenario.budget.id;orcamentoId=$scenario.budget.id;
        reparoId=$scenario.repair.id;ordemId=$scenario.repair.ordemId;decisao=$decision;ocorridoEm='2026-10-16T15:00:00Z'}
    Publish ($event|ConvertTo-Json -Compress)
}
function Wait-Ack($before) {
    for($n=0;$n -lt 30;$n++) {
        if([long](Queue-State 'execution.orcamento.v1').message_stats.ack -gt $before) { return }
        Start-Sleep -Seconds 1
    }
    throw 'Reentrega nao confirmada'
}
Wait-Health $execution
Wait-Health $billing
$a=New-Scenario
$null=Invoke-RestMethod "$billing/orcamentos/$($a.budget.id)/aprovar" -Method Post
$state=Wait-Status $a.repair.id 'NA_FILA'
if($state.orcamentoId -ne $a.budget.id) { throw 'Orcamento nao vinculado ao reparo' }
$null=Invoke-RestMethod "$execution/reparos/$($a.repair.id)/iniciar" -Method Post
# Wait until broker stats reflect the initial message before measuring the duplicate.
Start-Sleep -Seconds 6
$before=[long](Queue-State 'execution.orcamento.v1').message_stats.ack
Repeat-Event $a 'APROVADO'
Wait-Ack $before
if((Invoke-RestMethod "$execution/reparos/$($a.repair.id)").status -ne 'EM_EXECUCAO') { throw 'Reentrega regrediu reparo' }
if((Invoke-RestMethod "$execution/pecas/$($a.piece.id)").disponivel -ne 3) { throw 'Estoque consumido duas vezes' }
'PASS: aprovacao libera reparo e reentrega preserva execucao e estoque'
$r=New-Scenario
# Persist the decision with the broker unavailable, then recover from the Mongo outbox.
docker compose @composeArgs stop rabbitmq
if($LASTEXITCODE -ne 0) { throw 'Falha ao parar broker de teste' }
try {
    $result=Invoke-RestMethod "$billing/orcamentos/$($r.budget.id)/recusar" -Method Post
    if($result.status -ne 'RECUSADO') { throw 'Recusa nao persistida durante indisponibilidade' }
} finally {
    docker compose @composeArgs start rabbitmq
    if($LASTEXITCODE -ne 0) { throw 'Falha ao reiniciar broker' }
}
$null=Wait-Status $r.repair.id 'CANCELADO'
if((Invoke-RestMethod "$execution/pecas/$($r.piece.id)").disponivel -ne 5) { throw 'Reserva nao devolvida' }
'PASS: recusa salva com broker offline, entregue na recuperacao e reserva devolvida'
Start-Sleep -Seconds 6
$before=[long](Queue-State 'execution.orcamento.v1').message_stats.ack
Repeat-Event $r 'RECUSADO'
Wait-Ack $before
if((Invoke-RestMethod "$execution/pecas/$($r.piece.id)").disponivel -ne 5) { throw 'Reserva devolvida duas vezes' }
'PASS: recusa repetida nao duplica devolucao de estoque'
$beforeDlq=[long](Queue-State 'execution.orcamento.v1.dlq').messages
Repeat-Event $r 'APROVADO'
$dead=$false
for($n=0;$n -lt 30;$n++) {
    if([long](Queue-State 'execution.orcamento.v1.dlq').messages -gt $beforeDlq) { $dead=$true; break }
    Start-Sleep -Seconds 1
}
if(-not $dead) { throw 'Decisao conflitante nao chegou a DLQ' }
if((Invoke-RestMethod "$execution/reparos/$($r.repair.id)").status -ne 'CANCELADO') { throw 'Conflito alterou reparo' }
'PASS: decisao conflitante isolada na DLQ sem alterar reparo'
@{aprovado=$a.repair.id;recusado=$r.repair.id}|ConvertTo-Json
} finally { Pop-Location }
