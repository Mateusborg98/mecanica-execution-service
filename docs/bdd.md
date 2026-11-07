# BDD do ciclo do reparo

Os cenários executáveis ficam em `src/test/resources/features/reparo.feature`, escritos em Gherkin em português. `ReparoBddTest` executa Cucumber no JUnit Platform, e `ReparoSteps` liga cada passo às regras, persistência e endpoints do Execution.

## Comportamentos cobertos

1. Aprovação, início e conclusão: status persistido, datas, consumo da reserva e evento de conclusão pendente na outbox. Cancelamento posterior deve ser rejeitado.
2. Tentativa de início sem aprovação: HTTP 409, reparo ainda aguardando aprovação, reserva preservada e ausência de evento de conclusão.
3. Cancelamento antes do início, repetido: estoque devolvido uma única vez, reserva liberada, reparo cancelado e um único evento de cancelamento. Início posterior deve ser rejeitado.

Cada cenário recebe banco isolado. Não há mocks de gateways ou de transações: usamos implementações JDBC reais e MockMvc para executar os controllers sem abrir porta HTTP. A aprovação é inserida pelo caso de uso `ReparoService.liberar`, representando uma decisão já confirmada; estes cenários não exercitam Billing nem transporte RabbitMQ.

## Execução

Na raiz do Execution, com Java 21:

```powershell
.\mvnw.cmd clean verify
```

Para executar somente BDD:

```powershell
.\mvnw.cmd '-Dtest=ReparoBddTest' test
```

Para usar o PostgreSQL do ambiente de integração local:

```powershell
$env:RUN_POSTGRES_TESTS = 'true'
$env:TEST_POSTGRES_URL = 'jdbc:postgresql://localhost:15435/mecanica_execution'
.\mvnw.cmd '-Dtest=ReparoBddTest' test
Remove-Item Env:RUN_POSTGRES_TESTS
Remove-Item Env:TEST_POSTGRES_URL
```

O teste cria e remove um schema com prefixo `bdd_`, sem limpar dados de negócio. Sem a variável de ativação, usa H2 em modo PostgreSQL. A execução normal de `verify` mantém a exigência JaCoCo de 80%.

Relatórios: `target/cucumber/reparos.html`, `target/cucumber/reparos.json` e `target/surefire-reports`. A CI já executa `verify` com H2 e com PostgreSQL e agora inclui os relatórios Cucumber no artefato `execution-test-reports`. A execução com PostgreSQL sobrescreve o relatório HTML da primeira execução.

## Limites da evidência

Este é um fluxo completo dentro do serviço de execução. O fluxo entre OS, Execution e Billing foi acrescentado em módulo próprio, incluindo pagamento simulado e entrega: consulte [BDD distribuído](bdd-distribuido.md) e [evidência de 05/11](validacao-2026-11-05.md). Os roteiros PowerShell existentes continuam cobrindo comunicação real entre serviços; não são apresentados como cenários Cucumber.

Análise estática, execução remota da CI e pagamento real não são comprovados por esta suíte. O suporte ao JUnit Platform segue a [documentação oficial do Cucumber](https://github.com/cucumber/cucumber-jvm/blob/v8.0.4/cucumber-junit-platform-engine/README.md).

## Evidência de 04/11/2026

Os 3 cenários BDD passaram com H2. Em seguida, `clean verify` com `RUN_POSTGRES_TESTS=true` e PostgreSQL local aprovou 86 testes, incluindo os 3 cenários BDD, sem falhas ou testes ignorados. O JaCoCo confirmou a cobertura mínima de 80%. A CI foi configurada; execução no GitHub ainda não foi realizada nesta etapa.
