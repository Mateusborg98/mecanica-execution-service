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
$capture="teste.cancelamento.$([guid]::NewGuid())"
$created=$false
$reports=@()
function Publish([string]$route,[string]$payload) {
    $r=Post "$rabbit/exchanges/%2F/mecanica.events/publish" @{properties=@{content_type='application/json';delivery_mode=2};routing_key=$route;payload=$payload;payload_encoding='string'} $rabbitHeaders
    if(-not $r.routed) { throw "Evento não roteado: $route" }
}
try {
    $null=Invoke-RestMethod "$rabbit/queues/%2F/$capture" -Method Put -Headers $rabbitHeaders -ContentType 'application/json' -Body '{"durable":true,"auto_delete":false,"arguments":{"x-expires":600000}}'
    $created=$true
    foreach($route in @('reparo.cancelado.v1','orcamento.decidido.v1','orcamento.decidido.v2')) {
        $null=Post "$rabbit/bindings/%2F/e/mecanica.events/q/$capture" @{routing_key=$route;arguments=@{}} $rabbitHeaders
    }
    foreach($scenario in @('sem-diagnostico','aguardando','aprovado')) {
        $order=Post "$os/ordens-servico" @{placa='ABC1D23';matriculaOperador=1} $headers
        $link=Await "$os/ordens-servico/$($order.id)/reparo" {param($r) $r.publicado} $headers
        $repair=Await "$execution/reparos/$($link.reparoId)" {param($r) $r.status -eq 'AGUARDANDO_APROVACAO'}
        $budgetId=BudgetId $repair.id
        $piece=Post "$execution/pecas" @{sku="CANCEL-$([guid]::NewGuid())";descricao='Peça teste cancelamento';preco=25.50}
        $null=Post "$execution/pecas/$($piece.id)/entradas" @{movimentoId=[guid]::NewGuid().ToString();quantidade=5}
        if($scenario -ne 'sem-diagnostico') {
            $null=Post "$execution/reparos/$($repair.id)/diagnostico" @{observacoes='Cancelamento integrado';itens=@(@{tipo='PECA';referenciaId=$piece.id;quantidade=2})}
            $null=Post "$execution/reparos/$($repair.id)/reserva" @{itens=@(@{pecaId=$piece.id;quantidade=2})}
            $null=Await "$billing/orcamentos/$budgetId" {param($r) $r.status -eq 'AGUARDANDO_APROVACAO'}
            if((Invoke-RestMethod -Headers $operatorHeaders "$execution/pecas/$($piece.id)").disponivel -ne 3) { throw 'Reserva não descontada' }
        }
        if($scenario -eq 'aprovado') {
            $null=Invoke-RestMethod "$billing/orcamentos/$budgetId/aprovar" -Method Post
            $null=Await "$execution/reparos/$($repair.id)" {param($r) $r.status -eq 'NA_FILA'}
            $null=Await "$os/ordens-servico/acompanhamento/$($order.id)" {param($r) $r.status -eq 'NA_FILA'}
        }
        $cancelled=Invoke-RestMethod -Headers $operatorHeaders "$execution/reparos/$($repair.id)/cancelar" -Method Post
        if($cancelled.status -ne 'CANCELADO') { throw 'Execution não cancelou' }
        $null=Await "$os/ordens-servico/acompanhamento/$($order.id)" {param($r) $r.status -eq 'CANCELADA'}
        if($scenario -eq 'sem-diagnostico') {
            # Synthetic delayed event exercises ordering without reopening an actual canceled repair.
            $late=@{schemaVersion=1;eventType='DiagnosticoRegistrado';eventId=$repair.id;reparoId=$repair.id;ordemId=$order.id;ocorridoEm=$repair.criadoEm;itens=@(@{tipo='PECA';referenciaId=$piece.id;descricao='Peça teste cancelamento';quantidade=2;valorUnitario=25.50})}
            Publish 'diagnostico.registrado.v1' ($late|ConvertTo-Json -Depth 8 -Compress)
        }
        $null=Await "$billing/orcamentos/$budgetId" {param($r) $r.status -eq 'CANCELADO'}
        $null=Invoke-RestMethod -Headers $operatorHeaders "$execution/reparos/$($repair.id)/cancelar" -Method Post
        $expected=if($scenario -eq 'aprovado'){3}else{1}
        $null=Await "$rabbit/queues/%2F/$capture" {param($r) [long]$r.messages_ready -ge $expected} $rabbitHeaders
        $messages=Post "$rabbit/queues/%2F/$capture/get" @{count=100;ackmode='ack_requeue_false';encoding='auto';truncate=50000} $rabbitHeaders
        $events=@($messages|Where-Object { ($_.payload|ConvertFrom-Json).reparoId -eq $repair.id })
        if(-not ($events|Where-Object {$_.routing_key -eq 'reparo.cancelado.v1'})) { throw 'Cancelamento não capturado' }
        if($scenario -eq 'aprovado' -and @($events|Where-Object {$_.routing_key -like 'orcamento.decidido.*'}).Count -lt 2) { throw 'Aprovação não capturada' }
        Start-Sleep -Seconds 6
        $queues=@('billing.reparo-cancelado.v1','os.reparo-cancelado.v1')
        if($scenario -eq 'aprovado') {$queues+=@('execution.orcamento.v1','os.orcamento.v2')}
        $acks=@{};$dead=@{}
        foreach($queue in $queues) {
            $acks[$queue]=[long](Invoke-RestMethod "$rabbit/queues/%2F/$queue" -Headers $rabbitHeaders).message_stats.ack
            $dead[$queue]=[long](Invoke-RestMethod "$rabbit/queues/%2F/$queue.dlq" -Headers $rabbitHeaders).messages
        }
        foreach($event in $events) { Publish $event.routing_key $event.payload }
        foreach($queue in $queues) {
            $null=Await "$rabbit/queues/%2F/$queue" {param($r) [long]$r.message_stats.ack -gt $acks[$queue]} $rabbitHeaders
            if([long](Invoke-RestMethod "$rabbit/queues/%2F/$queue.dlq" -Headers $rabbitHeaders).messages -ne $dead[$queue]) { throw "Evento válido caiu na DLQ: $queue" }
        }
        if((Invoke-RestMethod -Headers $operatorHeaders "$execution/reparos/$($repair.id)").status -ne 'CANCELADO') { throw 'Reparo reaberto' }
        if((Invoke-RestMethod "$os/ordens-servico/acompanhamento/$($order.id)").status -ne 'CANCELADA') { throw 'OS reaberta' }
        if((Invoke-RestMethod "$billing/orcamentos/$budgetId").status -ne 'CANCELADO') { throw 'Orçamento reaberto' }
        if((Invoke-RestMethod -Headers $operatorHeaders "$execution/pecas/$($piece.id)").disponivel -ne 5) { throw 'Estoque devolvido incorretamente' }
        if($scenario -ne 'sem-diagnostico' -and (Invoke-RestMethod -Headers $operatorHeaders "$execution/reparos/$($repair.id)/reserva").status -ne 'LIBERADA') { throw 'Reserva não liberada' }
        if((Invoke-WebRequest "$billing/cobrancas/$budgetId" -SkipHttpErrorCheck).StatusCode -ne 404) { throw 'Cobrança criada após cancelamento' }
        foreach($check in @(@{url="$execution/reparos/$($repair.id)/iniciar";status=409},@{url="$billing/orcamentos/$budgetId/aprovar";status=400})) {
            if((Invoke-WebRequest $check.url -Headers $operatorHeaders -Method Post -SkipHttpErrorCheck).StatusCode -ne $check.status) { throw "Operação sobre cancelado deveria retornar $($check.status)" }
        }
        "PASS: $scenario -> OS/reparo/orçamento cancelados, estoque 5, sem cobrança; duplicatas/eventos atrasados não reabriram fluxo"
        $reports+=@{cenario=$scenario;ordemId=$order.id;reparoId=$repair.id;orcamentoId=$budgetId;estoqueFinal=5}
    }
    $report=@{validadoEm=[DateTimeOffset]::UtcNow.ToString('o');cenarios=$reports;pagamentoRealValidado=$false}
    $report|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $state 'ultima-validacao-cancelamento.json') -Encoding utf8
    $report|ConvertTo-Json -Depth 8
} finally {
    if($created) { Invoke-RestMethod "$rabbit/queues/%2F/$capture" -Method Delete -Headers $rabbitHeaders -TimeoutSec 10|Out-Null }
}
