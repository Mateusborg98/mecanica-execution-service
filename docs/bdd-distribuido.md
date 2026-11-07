# BDD entre OS, Execution e Billing

O módulo Java `tests/fluxo-distribuido` executa Cucumber com chamadas HTTP reais às três aplicações, PostgreSQL, MongoDB e RabbitMQ. Somente o provedor externo de pagamento é substituído por um servidor HTTP Java.

## Executar

Requisitos: Java 21, Docker Desktop com motor Linux e os três repositórios em diretórios irmãos. Na raiz do Execution:

```powershell
./mvnw.cmd -B -f tests/fluxo-distribuido/pom.xml verify
```

O teste cria uma chave RSA temporária em memória e grava apenas a chave pública em `target/bdd-public.env`. O Compose `mecanica-bdd-local` usa portas 28081/28082/28083 e 28090; bancos e broker têm volumes exclusivos desse projeto. As credenciais são fictícias. O ambiente integrado normal nas portas 18081/18082/18083 não é alterado.

O encerramento chama `docker compose down` sem remover volumes. Se o processo for interrompido abruptamente, execute `docker compose -f compose.bdd.yaml down`. Os dados de teste persistem e cada cenário cria novos identificadores.

Relatórios: `tests/fluxo-distribuido/target/cucumber/fluxo.html`, `fluxo.json` e `target/surefire-reports`. O status de execução deve ser conferido nos relatórios; a existência dos cenários não comprova que passaram.

## Cenários

- Abertura autenticada da OS; criação assíncrona do reparo; diagnóstico, orçamento e reserva de peças; aprovação; início e conclusão; cobrança; entrega bloqueada; checkout; assinatura inválida rejeitada; webhook assinado repetido; pagamento confirmado; entrega e histórico sem pagamento duplicado.
- Recusa do orçamento; cancelamento distribuído da OS/reparo, devolução do estoque e ausência de cobrança.

O teste não escreve diretamente nos bancos para forçar estados. A consulta ao pagamento passa pelo adaptador HTTP do Billing e a confirmação chega à OS pelo RabbitMQ.

## Limite financeiro

O simulador não efetua compras nem emite links utilizáveis. Sua URL alternativa só é aceita no perfil `bdd`, com token fictício específico e hostname `provider`. O ambiente real continua usando a API oficial. Este BDD não substitui a compra com conta de teste do Mercado Pago, o webhook real nem a evidência externa pendente.

## Evidência

Os dois cenários passaram em 05/11/2026. [Resultado, cobertura e limites](validacao-2026-11-05.md). O módulo também aplica PMD ao simulador Java.
