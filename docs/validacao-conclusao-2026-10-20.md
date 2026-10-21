# Validação: conclusão e cobrança — 20/10/2026

## Suítes automatizadas

- Billing: 100 testes, zero falhas, erros ou ignorados com MongoDB real (`RUN_MONGO_TESTS=true`). `clean verify` aprovado, incluindo exigência de cobertura mínima de 80% sem exclusões.
- Execution: 63 testes, zero falhas, erros ou ignorados em H2 e em PostgreSQL real (`RUN_POSTGRES_TESTS=true`). Build, testes e cobertura mínima aprovados nos dois modos.
- Billing: `clean verify` também aprovado no modo sem MongoDB; testes de banco externo ficam ignorados nesse modo.

Novos cenários: transação que salva estado e outbox; rollback da conclusão quando a outbox falha; concorrência de conclusões e publicadores; retentativa com mesmo evento; validação de orçamento aprovado, correlação e datas; cobrança sem duplicidade; preservação de valor e data; erros transitórios; consulta HTTP 200/404/400 e ausência de alteração de pagamento por POST.

## Demonstração com APIs e RabbitMQ reais

As imagens dos dois serviços foram reconstruídas no `compose.integration.yaml`. O script `scripts/validar-conclusao-cobranca.ps1` passou nos quatro cenários:

1. Aprovação e início do reparo não geram cobrança antecipada.
2. Conclusão é salva com o broker offline. Após a recuperação, o Billing registra cobrança PENDENTE de BRL 51,00, correspondente às duas peças de BRL 25,50 do orçamento aprovado.
3. Reentrega preserva a mesma cobrança, valor, estado e data de criação. Nenhum pagamento é confirmado.
4. Mensagem com data de conclusão conflitante é isolada na DLQ sem modificar a cobrança.

Dados fictícios locais preservados:

- Reparo: `e4d9e5b5-2eb4-4483-9b46-b8e20c14721e`.
- Orçamento/cobrança: `aae8be64-dc2c-3a98-87bb-271ee5a4efa1`.
- Ordem: `b06a9c4e-6890-43a3-a4e7-c6fde5d86adc`.
- Conclusão em UTC: `2026-10-21T00:01:16.853Z` (20/10, horário de São Paulo).

Essa validação não usou Mercado Pago nem provisionou recursos AWS. Não representa pagamento real, entrega liberada ou Saga completa. As credenciais e a escolha do produto Mercado Pago continuam pendentes. Registros históricos não foram migrados/republicados automaticamente.
