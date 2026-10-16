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
    $hash=[Security.Cryptography.MD5]::HashData($bytes)
    $hash[6]=($hash[6] -band 15) -bor 48
    $hash[8]=($hash[8] -band 63) -bor 128
    $hex=[Convert]::ToHexString($hash).ToLowerInvariant()
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
function Publish($payload) {
    $request=@{properties=@{content_type='application/json'; delivery_mode=2};routing_key='diagnostico.registrado.v1';payload=$payload;payload_encoding='string'}
    $response=Invoke-RestMethod "$rabbit/exchanges/%2F/mecanica.events/publish" -Method Post -Headers $headers -ContentType 'application/json' -Body ($request|ConvertTo-Json -Depth 10)
    if (-not $response.routed) { throw 'Message not routed' }
}
Wait-Health $execution
Wait-Health $billing
$tag=[guid]::NewGuid().ToString()
$p=Post-Json "$execution/pecas" @{sku="INTEGRACAO-$tag";descricao='Filtro';preco=25.50}
$s=Post-Json "$execution/servicos" @{codigo="INTEGRACAO-$tag";descricao='Troca de filtro';preco=100.00}
$r=Post-Json "$execution/reparos" @{ordemId=[guid]::NewGuid().ToString()}
$diag=Post-Json "$execution/reparos/$($r.id)/diagnostico" @{observacoes='Validacao integrada';itens=@(@{tipo='PECA';referenciaId=$p.id;quantidade=2},@{tipo='SERVICO';referenciaId=$s.id;quantidade=1})}
$budgetId=Budget-Id $r.id
$budget=Wait-Budget $budgetId
if ($budget.valorTotal -ne 151 -or $budget.ordemId -ne $r.ordemId -or $budget.status -ne 'AGUARDANDO_APROVACAO') { throw 'Wrong budget data' }
'PASS: diagnosis delivered by RabbitMQ and persisted in Billing with total 151.00'
# Wait for broker statistics before measuring the duplicate delivery.
for($n=0;$n -lt 20;$n++) { $q=Queue-State 'billing.diagnostico.v1'; if ($q.message_stats.ack -ge 1) { break }; Start-Sleep -Seconds 1 }
$before=[long]$q.message_stats.ack
$approved=Invoke-RestMethod "$billing/orcamentos/$budgetId/aprovar" -Method Post
$event=@{schemaVersion=1;eventType='DiagnosticoRegistrado';eventId=$r.id;reparoId=$r.id;ordemId=$r.ordemId;ocorridoEm=$diag.criadoEm;itens=$diag.itens}
Publish ($event|ConvertTo-Json -Depth 10 -Compress)
$acked=$false
for($n=0;$n -lt 25;$n++) { $q=Queue-State 'billing.diagnostico.v1'; if ([long]$q.message_stats.ack -gt $before) { $acked=$true; break }; Start-Sleep -Seconds 1 }
if (-not $acked) { throw 'Duplicate event was not acknowledged' }
if ((Invoke-RestMethod "$billing/orcamentos/$budgetId").status -ne 'APROVADO') { throw 'Redelivery overwrote approval' }
'PASS: repeated message acknowledged without overwriting approval'
# A temporary broker outage must not prevent recording a diagnosis.
docker compose @composeArgs stop rabbitmq
if ($LASTEXITCODE -ne 0) { throw 'Could not stop test broker' }
try {
    $r2=Post-Json "$execution/reparos" @{ordemId=[guid]::NewGuid().ToString()}
    $diag2=Post-Json "$execution/reparos/$($r2.id)/diagnostico" @{observacoes='Broker outage test';itens=@(@{tipo='SERVICO';referenciaId=$s.id;quantidade=1})}
    if ($diag2.valorTotal -ne 100) { throw 'Diagnosis not stored during outage' }
} finally {
    docker compose @composeArgs start rabbitmq
    if ($LASTEXITCODE -ne 0) { throw 'Could not restart test broker' }
}
$budget2=Wait-Budget (Budget-Id $r2.id)
if ($budget2.valorTotal -ne 100) { throw 'Pending diagnosis was not recovered' }
'PASS: diagnosis recorded during broker outage and delivered after recovery'
$beforeDlq=[long](Queue-State 'billing.diagnostico.v1.dlq').messages
Publish '{"schemaVersion":99}'
$dead=$false
for($n=0;$n -lt 25;$n++) { if ([long](Queue-State 'billing.diagnostico.v1.dlq').messages -gt $beforeDlq) { $dead=$true; break }; Start-Sleep -Seconds 1 }
if (-not $dead) { throw 'Invalid event did not reach DLQ' }
'PASS: invalid event isolated in dead-letter queue'
# Keep the isolated demonstration data for inspection; do not touch standalone project databases.
@{reparoId=$r.id;orcamentoId=$budgetId;reparoRecuperadoId=$r2.id;orcamentoRecuperadoId=(Budget-Id $r2.id)} | ConvertTo-Json

} finally { Pop-Location }
