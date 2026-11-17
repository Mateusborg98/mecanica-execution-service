# Validação de estoque e reservas — 14/10/2026

- H2: 25 testes, nenhuma falha ou teste ignorado.
- PostgreSQL 16: 25 testes, nenhuma falha ou teste ignorado.
- JaCoCo: 202/205 linhas, 98,54%, sem exclusões de classes.
- Concorrência: duas reservas disputando saldo, entrada duplicada e início versus cancelamento.
- Rollback: falta de saldo em um item desfaz toda a reserva; falha de devolução também desfaz o cancelamento.
- Docker: imagem construída, migração V2 aplicada sobre V1 e health UP.
- HTTP real: peça criada, entrada repetida sem duplicação, reserva repetida sem novo débito,
  reserva persistida após reinício e saldo devolvido ao cancelar o reparo.
- Os registros temporários foram removidos. Os volumes foram preservados.
- CI existente executará a suíte ampliada em H2 e PostgreSQL; execução remota não realizada.

Catálogo de serviços, diagnóstico e mensageria/Saga permanecem pendentes. A integração
com Billing ainda é necessária para liberar reparos a partir de aprovações reais.
