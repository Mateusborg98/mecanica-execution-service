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

function Assert-NoCharge($id) {
    try { $null=Invoke-RestMethod "$billing/cobrancas/$id" -TimeoutSec 5 }
    catch { if([int]$_.Exception.Response.StatusCode -eq 404) { return }; throw }
    throw 'Cobranca criada antes da conclusao'
}
function Wait-Charge($id) {
    for($n=0;$n -lt 90;$n++) {
        try { return Invoke-RestMethod "$billing/cobrancas/$id" -TimeoutSec 3 }
        catch { if([int]$_.Exception.Response.StatusCode -ne 404) { throw } }
        Start-Sleep -Seconds 1
    }
    throw "Cobranca nao recebida: $id"
}
function Completion-Event($scenario,$completed) {
    return @{schemaVersion=1;eventType='ReparoConcluido';eventId=$scenario.repair.id;reparoId=$scenario.repair.id;
        ordemId=$scenario.repair.ordemId;orcamentoId=$scenario.budget.id;ocorridoEm=$completed.concluidoEm}
}
Wait-Health $execution
Wait-Health $billing
$scenario=New-Scenario
Assert-NoCharge $scenario.budget.id
$null=Invoke-RestMethod "$billing/orcamentos/$($scenario.budget.id)/aprovar" -Method Post
$null=Wait-Status $scenario.repair.id 'NA_FILA'
$null=Invoke-RestMethod "$execution/reparos/$($scenario.repair.id)/iniciar" -Method Post
Assert-NoCharge $scenario.budget.id
'PASS: aprovacao e inicio nao geram cobranca antecipada'
docker compose @composeArgs stop rabbitmq
if($LASTEXITCODE -ne 0) { throw 'Falha ao parar broker de teste' }
try {
    $completed=Invoke-RestMethod "$execution/reparos/$($scenario.repair.id)/concluir" -Method Post
    if($completed.status -ne 'CONCLUIDO') { throw 'Reparo nao concluido durante indisponibilidade' }
    Assert-NoCharge $scenario.budget.id
} finally {
    docker compose @composeArgs start rabbitmq
    if($LASTEXITCODE -ne 0) { throw 'Falha ao reiniciar broker' }
}
$charge=Wait-Charge $scenario.budget.id
if($charge.status -ne 'PENDENTE' -or $charge.valorTotal -ne 51 -or $charge.moeda -ne 'BRL' -or $charge.reparoId -ne $scenario.repair.id) {
    throw 'Cobranca recebida com dados incorretos'
}
'PASS: conclusao salva com broker offline e cobranca pendente de BRL 51.00 criada apos recuperacao'
Start-Sleep -Seconds 6
$before=[long](Queue-State 'billing.reparo-concluido.v1').message_stats.ack
$event=Completion-Event $scenario $completed
Publish ($event|ConvertTo-Json -Compress) 'reparo.concluido.v1'
$acked=$false
for($n=0;$n -lt 30;$n++) {
    if([long](Queue-State 'billing.reparo-concluido.v1').message_stats.ack -gt $before) { $acked=$true; break }
    Start-Sleep -Seconds 1
}
if(-not $acked) { throw 'Reentrega nao confirmada' }
$again=Invoke-RestMethod "$billing/cobrancas/$($scenario.budget.id)"
if($again.criadaEm -ne $charge.criadaEm -or $again.valorTotal -ne $charge.valorTotal -or $again.status -ne 'PENDENTE') { throw 'Reentrega sobrescreveu cobranca' }
'PASS: reentrega preserva cobranca e nao confirma pagamento'
$beforeDlq=[long](Queue-State 'billing.reparo-concluido.v1.dlq').messages
$event.ocorridoEm=([DateTimeOffset]$completed.concluidoEm).AddMinutes(1).ToString('o')
Publish ($event|ConvertTo-Json -Compress) 'reparo.concluido.v1'
$dead=$false
for($n=0;$n -lt 30;$n++) {
    if([long](Queue-State 'billing.reparo-concluido.v1.dlq').messages -gt $beforeDlq) { $dead=$true; break }
    Start-Sleep -Seconds 1
}
if(-not $dead) { throw 'Conclusao conflitante nao chegou a DLQ' }
if((Invoke-RestMethod "$billing/cobrancas/$($scenario.budget.id)").reparoConcluidoEm -ne $charge.reparoConcluidoEm) { throw 'Conflito alterou cobranca' }
'PASS: conclusao conflitante vai para DLQ sem alterar cobranca'
@{reparoId=$scenario.repair.id;orcamentoId=$scenario.budget.id;cobranca=$charge}|ConvertTo-Json -Depth 5
} finally { Pop-Location }
