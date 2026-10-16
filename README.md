# MecÃ¢nica Execution Service â€” Tech Challenge Fase 4

MicrosserviÃ§o responsÃ¡vel pelo diagnÃ³stico, estoque e execuÃ§Ã£o dos serviÃ§os da oficina mecÃ¢nica.

Este projeto faz parte da arquitetura de microsserviÃ§os desenvolvida para o Tech Challenge da Fase 4.

## Responsabilidades

- Registrar o diagnÃ³stico e identificar as peÃ§as e os serviÃ§os necessÃ¡rios.
- Gerenciar os catÃ¡logos de peÃ§as e serviÃ§os.
- Controlar entradas, reservas, liberaÃ§Ãµes e consumo de estoque.
- Gerenciar a fila de execuÃ§Ã£o das ordens aprovadas.
- Registrar o inÃ­cio e a conclusÃ£o dos reparos.
- Comunicar o andamento e a conclusÃ£o da execuÃ§Ã£o ao serviÃ§o de ordens de serviÃ§o.
- Executar as compensaÃ§Ãµes de sua responsabilidade, como liberar reservas quando o trabalho ainda nÃ£o tiver sido iniciado.

## Dados sob responsabilidade do serviÃ§o

- CatÃ¡logo de peÃ§as.
- CatÃ¡logo de serviÃ§os.
- Estoque e suas movimentaÃ§Ãµes.
- Reservas de peÃ§as.
- DiagnÃ³sticos.
- Fila de execuÃ§Ã£o.
- Registros de inÃ­cio e conclusÃ£o dos reparos.
- ReferÃªncias aos identificadores da OS e do operador responsÃ¡vel.

## IntegraÃ§Ã£o com os demais serviÃ§os

- mecanica-os-service: recebimento de solicitaÃ§Ãµes e comunicaÃ§Ã£o do andamento da execuÃ§Ã£o.
- mecanica-billing-service: fornecimento das informaÃ§Ãµes necessÃ¡rias Ã  elaboraÃ§Ã£o dos orÃ§amentos, conforme os contratos de integraÃ§Ã£o.

Cada microsserviÃ§o Ã© responsÃ¡vel por seus prÃ³prios dados. A comunicaÃ§Ã£o entre eles ocorre por contratos de API e mensagens, sem acesso direto ao banco de outro serviÃ§o.

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
O retorno da decisão do Billing e a Saga completa ainda estão pendentes.

## Integração de diagnóstico e orçamento — 16/10/2026

Execution publica diagnósticos via outbox/RabbitMQ; Billing cria orçamentos de forma
idempotente. Veja [fluxo, execução local e limites](docs/integracao-diagnostico-rabbitmq.md).
O retorno de aprovação/recusa e a Saga completa ainda estão pendentes.
