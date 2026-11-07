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
$unauthorized=Invoke-WebRequest "$os/ordens-servico" -SkipHttpErrorCheck
if($unauthorized.StatusCode -notin @(401,403)) { throw 'OS aceitou consulta administrativa sem JWT' }
$order=Post "$os/ordens-servico" @{placa='ABC1D23';matriculaOperador=1} $headers
$link=Await "$os/ordens-servico/$($order.id)/reparo" {param($r) $r.publicado} $headers
$repair=Await "$execution/reparos/$($link.reparoId)" {param($r) $r.status -eq 'AGUARDANDO_APROVACAO'}
if($repair.ordemId -ne $order.id) { throw 'Reparo não corresponde à OS' }
'PASS: abertura autenticada da OS criou reparo automaticamente via RabbitMQ'
$piece=Post "$execution/pecas" @{sku="OS-$([guid]::NewGuid())";descricao='Filtro de teste integrado';preco=25.50}
$null=Post "$execution/pecas/$($piece.id)/entradas" @{movimentoId=[guid]::NewGuid().ToString();quantidade=5}
$null=Post "$execution/reparos/$($repair.id)/diagnostico" @{observacoes='Teste OS real';itens=@(@{tipo='PECA';referenciaId=$piece.id;quantidade=2})}
$null=Post "$execution/reparos/$($repair.id)/reserva" @{itens=@(@{pecaId=$piece.id;quantidade=2})}
$budget=Await "$billing/orcamentos/$(BudgetId $repair.id)" {param($r) $r.status -eq 'AGUARDANDO_APROVACAO'}
$null=Await "$os/ordens-servico/acompanhamento/$($order.id)" {param($r) $r.status -eq 'AGUARDANDO_APROVACAO'}
'PASS: diagnóstico projetou AGUARDANDO_APROVACAO na OS'
$null=Invoke-RestMethod "$billing/orcamentos/$($budget.id)/aprovar" -Method Post
$approved=Await "$os/ordens-servico/acompanhamento/$($order.id)" {param($r) $r.status -eq 'NA_FILA'}
if($approved.valorTotal -ne 51) { throw 'Valor aprovado divergente na OS' }
'PASS: aprovação projetou NA_FILA e R$ 51,00 na OS'
$null=Await "$execution/reparos/$($repair.id)" {param($r) $r.status -eq 'NA_FILA'}
$started=Invoke-RestMethod -Headers $operatorHeaders "$execution/reparos/$($repair.id)/iniciar" -Method Post
$running=Await "$os/ordens-servico/acompanhamento/$($order.id)" {param($r) $r.status -eq 'EM_EXECUCAO'}
if(-not $running.inicioExecucao) { throw 'OS sem data de início' }
'PASS: início projetou EM_EXECUCAO e data na OS'
$completed=Invoke-RestMethod -Headers $operatorHeaders "$execution/reparos/$($repair.id)/concluir" -Method Post
$projected=Await "$os/ordens-servico/acompanhamento/$($order.id)" {param($r) $r.status -eq 'FINALIZADA'}
if(-not $projected.fimExecucao) { throw 'OS finalizada sem data de conclusão' }
$charge=Await "$billing/cobrancas/$($budget.id)" {param($r) $r.status -eq 'PENDENTE'}
if($charge.valorTotal -ne 51 -or $charge.ordemId -ne $order.id) { throw 'Cobrança incorreta' }
'PASS: conclusão do Execution finalizou a OS e gerou cobrança PENDENTE no Billing'
$delivery=Invoke-WebRequest "$os/ordens-servico/$($order.id)/entregar" -Method Post -Headers $headers -SkipHttpErrorCheck
if($delivery.StatusCode -ne 422) { throw "Entrega sem pagamento não foi bloqueada: $($delivery.StatusCode)" }
'PASS: entrega sem pagamento foi bloqueada'
$rabbitHeaders=@{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('mecanica_local:mecanica_local_password'))}
$queue='http://127.0.0.1:15673/api/queues/%2F/os.reparo-concluido.v1'
$before=Await $queue {param($r) [long]$r.message_stats.ack -ge 1 -and [long]$r.messages -eq 0 -and [long]$r.messages_unacknowledged -eq 0} $rabbitHeaders
$event=@{schemaVersion=1;eventType='ReparoConcluido';eventId=$repair.id;reparoId=$repair.id;ordemId=$order.id;orcamentoId=$budget.id;ocorridoEm=$completed.concluidoEm}|ConvertTo-Json -Compress
$published=Post 'http://127.0.0.1:15673/api/exchanges/%2F/mecanica.events/publish' @{properties=@{content_type='application/json';delivery_mode=2};routing_key='reparo.concluido.v1';payload=$event;payload_encoding='string'} $rabbitHeaders
if(-not $published.routed) { throw 'Reentrega não roteada' }
$null=Await $queue {param($r) [long]$r.message_stats.ack -gt [long]$before.message_stats.ack} $rabbitHeaders
$after=Invoke-RestMethod "$os/ordens-servico/acompanhamento/$($order.id)"
if($after.status -ne 'FINALIZADA' -or $after.fimExecucao -ne $projected.fimExecucao) { throw 'Reentrega alterou conclusão' }
 'PASS: reentrega da conclusão foi confirmada sem alterar o resultado'
$startQueue='http://127.0.0.1:15673/api/queues/%2F/os.reparo-iniciado.v1'
$startBefore=Await $startQueue {param($r) [long]$r.message_stats.ack -ge 1 -and [long]$r.messages -eq 0} $rabbitHeaders
$startEvent=@{schemaVersion=1;eventType='ReparoIniciado';eventId=$repair.id;reparoId=$repair.id;ordemId=$order.id;orcamentoId=$budget.id;ocorridoEm=$started.iniciadoEm}|ConvertTo-Json -Compress
$routed=Post 'http://127.0.0.1:15673/api/exchanges/%2F/mecanica.events/publish' @{properties=@{content_type='application/json';delivery_mode=2};routing_key='reparo.iniciado.v1';payload=$startEvent;payload_encoding='string'} $rabbitHeaders
if(-not $routed.routed) { throw 'Início repetido não roteado' }
$null=Await $startQueue {param($r) [long]$r.message_stats.ack -gt [long]$startBefore.message_stats.ack} $rabbitHeaders
$after=Invoke-RestMethod "$os/ordens-servico/acompanhamento/$($order.id)"
if($after.status -ne 'FINALIZADA' -or $after.valorTotal -ne 51 -or $after.inicioExecucao -ne $running.inicioExecucao) { throw 'Início atrasado regrediu a OS' }
'PASS: início repetido após conclusão não regrediu status, valor ou data'

$report=@{validadoEm=[DateTimeOffset]::UtcNow.ToString('o');ordemId=$order.id;reparoId=$repair.id;orcamentoId=$budget.id;valorCobranca=51;valorOs=$after.valorTotal;statusOs=$after.status;statusCobranca=$charge.status;pagamentoRealValidado=$false}
$report|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $state 'ultima-validacao-os-execution.json') -Encoding utf8
$report|ConvertTo-Json
