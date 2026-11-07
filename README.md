# Mecânica Execution Service — Tech Challenge Fase 4

Microsserviço responsável pelo diagnóstico, estoque e execução dos serviços da oficina mecânica.

Este projeto faz parte da arquitetura de microsserviços desenvolvida para o Tech Challenge da Fase 4.

## Responsabilidades

- Registrar o diagnóstico e identificar as peças e os serviços necessários.
- Gerenciar os catálogos de peças e serviços.
- Controlar entradas, reservas, liberações e consumo de estoque.
- Gerenciar a fila de execução das ordens aprovadas.
- Registrar o início e a conclusão dos reparos.
- Comunicar o andamento e a conclusão da execução ao serviço de ordens de serviço.
- Executar as compensações de sua responsabilidade, como liberar reservas quando o trabalho ainda não tiver sido iniciado.

## Dados sob responsabilidade do serviço

- Catálogo de peças.
- Catálogo de serviços.
- Estoque e suas movimentações.
- Reservas de peças.
- Diagnósticos.
- Fila de execução.
- Registros de início e conclusão dos reparos.
- Referências aos identificadores da OS e do operador responsável.

## Integração com os demais serviços

- mecanica-os-service: recebimento de solicitações e comunicação do andamento da execução.
- mecanica-billing-service: fornecimento das informações necessárias à elaboração dos orçamentos, conforme os contratos de integração.

Cada microsserviço é responsável por seus próprios dados. A comunicação entre eles ocorre por contratos de API e mensagens, sem acesso direto ao banco de outro serviço.

## Status do projeto

Em desenvolvimento. As responsabilidades descritas representam a arquitetura planejada para a Fase 4.
## Avanço de 14/10/2026

Base Spring Boot executável, PostgreSQL local independente, regras do ciclo de reparo,
testes, Dockerfile e CI com cobertura mínima de 80%.
Consulte [o estado real da implementação e os comandos](docs/estado-implementacao.md).
A API REST e a persistência PostgreSQL do reparo estão implementadas. Catálogo de peças, estoque e reservas transacionais também estão implementados; integração entre serviços continua pendente.

Veja o [contrato de estoque e reservas](docs/estoque-e-reservas.md) e os testes de concorrência.

## Avanço de 16/10/2026

Catálogo de serviços e diagnóstico com preços preservados, repetição segura e validação
das peças reservadas antes do início. Veja [serviços e diagnóstico](docs/servicos-e-diagnostico.md).
O retorno de aprovação/recusa está implementado; a Saga completa permanece pendente.

## Integração de diagnóstico e orçamento — 16/10/2026

Execution publica diagnósticos via outbox/RabbitMQ; Billing cria orçamentos de forma
idempotente. Veja [fluxo, execução local e limites](docs/integracao-diagnostico-rabbitmq.md).
O retorno de aprovação/recusa está implementado; a Saga completa permanece pendente.

## Aprovação e recusa integradas

O Billing publica a decisão do orçamento por RabbitMQ. O Execution libera o reparo aprovado ou cancela o recusado, devolvendo reservas sem duplicidade. Consulte [o fluxo, a validação e os limites](docs/decisao-orcamento-rabbitmq.md).

## Conclusão e cobrança pendente

A conclusão do reparo chega ao Billing por RabbitMQ e cria uma única cobrança interna PENDENTE, com o valor do orçamento aprovado. Não há cobrança real no Mercado Pago nesta etapa. Consulte [fluxo, falhas e demonstração](docs/conclusao-e-cobranca.md).

## Solicitação de reparo pela OS

O consumidor de ReparoSolicitado cria o reparo de forma idempotente. Consulte [o contrato e os limites da integração](docs/abertura-os-execution.md).


## OS no ambiente integrado

Veja [inicialização, autenticação local e roteiro de conclusão](docs/integracao-os-conclusao.md).

[Progresso e valor da OS](docs/progresso-os.md) — contratos, validação integrada e limitações atuais.


## Saga e recuperação de falhas

A integração usa Saga por coreografia. Consulte [etapas, compensação de reservas, roteiro de falha e limites pendentes](docs/saga.md).


## Cancelamento integrado

Cancelamentos antes do início propagam para OS e Billing, liberam reservas e toleram reentregas. Veja [regras, contrato e roteiro de validação](docs/cancelamento-integrado.md).

- [Reprocessamento auditável de DLQ](docs/reprocessamento-dlq.md): operação local, limites e evidências.

- [BDD do ciclo do reparo](docs/bdd.md): cenários, execução e limites.

- [Análise estática e critérios de aprovação](docs/qualidade-estatica.md): regras PMD, execução e relatórios.

[Validação local de 05/11/2026](docs/validacao-2026-11-05.md): OS, Billing e BDD distribuído; limites e pendências explícitos.

## Permissões de acesso

As operações administrativas exigem JWT de OPERADOR ou ADMINISTRADOR. Consulte [a matriz de permissões e configuração local](docs/seguranca-e-permissoes.md). As rotas públicas de acompanhamento, orçamento e checkout permanecem conforme o contrato documentado.


## Preparação de implantação da Fase 4

Os [manifests Kubernetes](k8s/README.md) e o workflow CD estão preparados. A infraestrutura compartilhada e a estimativa de custos ficam em mecanica-os-service/infra/fase4/README.md. A preparação local não comprova provisionamento AWS ou execução remota do CD.
