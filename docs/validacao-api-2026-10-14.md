# Validação da API de reparos — 14/10/2026

- Maven verify com H2: 13 testes, sem falhas ou testes ignorados.
- Maven verify com PostgreSQL 16: 13 testes, sem falhas ou testes ignorados.
- JaCoCo: 87/90 linhas, 96,67%, sem exclusões de classes.
- Concorrência: início e cancelamento sobre a mesma versão; exatamente um vence.
- Regressão: operações no mesmo instante com precisão de microssegundos.
- Docker Compose: imagem construída, Flyway V1 aplicada e health UP.
- HTTP real: criação, bloqueio do início sem aprovação (409), consulta após reinício
  do contêiner e cancelamento confirmados. O registro temporário foi removido.
- CI atualizado localmente para executar os testes também com PostgreSQL; não publicado.

A liberação após aprovação está disponível no serviço de aplicação e coberta pelos
 testes. O consumidor que receberá a aprovação real do Billing ainda não foi implementado.
Próximo bloco: catálogo/estoque/reservas e integração confiável de eventos para a Saga.
