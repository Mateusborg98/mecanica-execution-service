# Mecânica Execution Service — Fase 4

Microsserviço Java 21 / Spring Boot responsável por diagnóstico, catálogo, estoque e reparos. Mantém PostgreSQL próprio e comunica-se com OS e Billing por RabbitMQ, sem acessar bancos de outros serviços.

## Comportamento implementado

Solicitações da OS criam reparos idempotentes. O diagnóstico preserva os preços dos itens e gera orçamento no Billing. A aprovação libera o reparo para a fila; a recusa cancela e libera reservas. Início e conclusão publicam progresso para a OS; a conclusão também origina a cobrança no Billing. A OS exige pagamento confirmado antes da entrega.

`GET /reparos/fila?limite=50` lista somente NA_FILA, em ordem de criação do atendimento e UUID como desempate. Aceita limites de 1 a 100. A ordenação apresenta os atendimentos mais antigos entre os aprovados; não obriga o operador a iniciar somente o primeiro.

Operações exigem JWT de OPERADOR ou ADMINISTRADOR. Consulte [permissões](docs/seguranca-e-permissoes.md).

## Saga e compensações

A Saga coreografada evita acoplamento a um coordenador central: transações locais, outboxes e consumidores idempotentes propagam as transições. Falhas temporárias são repetidas; mensagens incompatíveis vão para DLQ. Falhas definitivas permanecem pendentes para correção ou cancelamento explícito antes da execução.

Cancelamentos liberam reservas uma vez e propagam CANCELADA para OS e orçamento. `POST /reparos/cancelamentos` aceita os IDs reservados pela OS e funciona mesmo antes da criação do reparo. Uma solicitação tardia não reabre o atendimento. Reparos iniciados/concluídos não têm cancelamento físico automático: usa-se recuperação para frente. Veja [política de falhas](docs/politica-falhas-saga.md) e [cancelamento](docs/cancelamento-integrado.md).

## Executar e validar

Java 21, Docker e Maven Wrapper. Para iniciar o ambiente integrado, use `scripts/iniciar-integracao-os.ps1`. Veja [roteiro local](docs/integracao-os-conclusao.md).

- `./mvnw clean verify`: testes, BDD de domínio, JaCoCo mínimo de 80% e PMD.
- `RUN_POSTGRES_TESTS=true`: habilita a execução com PostgreSQL real; configure `TEST_POSTGRES_URL` para o banco de teste.
- `./mvnw -B -f tests/fluxo-distribuido/pom.xml verify`: BDD com os três serviços, bancos e RabbitMQ em Docker. Mantenha os três repositórios lado a lado.

No PowerShell use `.\mvnw.cmd`. O BDD usa pagamento simulado; não substitui a compra real de teste Mercado Pago. Os cenários distribuídos cobrem pagamento antes da entrega, recusa com restituição de estoque e cancelamento antes da criação durante indisponibilidade do broker.

## Guias e entrega

- [Estoque e reservas](docs/estoque-e-reservas.md), [diagnóstico](docs/servicos-e-diagnostico.md) e [solicitação de reparo](docs/abertura-os-execution.md).
- [Saga](docs/saga.md), [DLQ](docs/reprocessamento-dlq.md) e [qualidade](docs/qualidade-estatica.md).
- [Kubernetes](k8s/README.md); infraestrutura compartilhada em `mecanica-os-service/infra/fase4`.

Os pipelines de CI/CD são independentes. Deploy AWS/CD real e observabilidade Datadog no ambiente entregue ainda precisam de evidência. Documentos com data são registros históricos de validações, não declarações de conclusão de todas as etapas.