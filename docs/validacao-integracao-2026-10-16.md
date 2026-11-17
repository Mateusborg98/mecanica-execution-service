# Validação da integração — 16/10/2026

- Execution: 44 testes em PostgreSQL, zero falhas, zero erros, zero ignorados.
- Billing: 73 testes com MongoDB, zero falhas, zero erros, zero ignorados.
- Cobertura de linhas sem exclusões: Execution 349/368 (94,84%); Billing 244/274 (89,05%).
- Ambas as imagens Docker construídas e iniciadas com RabbitMQ real.
- Diagnóstico de 151,00 recebido no Billing e persistido como orçamento pendente.
- Reenvio confirmado pelo consumidor preservou o orçamento previamente aprovado.
- Diagnóstico registrado durante indisponibilidade do broker foi entregue após recuperação.
- Mensagem inválida isolada na DLQ.
- Migrações V1–V4 aplicadas no PostgreSQL de integração; backfill V4 também coberto por teste.
- A pendência de testar diagnóstico/outbox em banco real foi resolvida nesta rodada.
- CI foi preparado localmente; não houve push nem execução remota no GitHub.

O ambiente integrado usa volumes próprios. Os dados de demonstração e a mensagem de erro
foram mantidos para inspeção. A aprovação ainda não é devolvida automaticamente ao Execution.
