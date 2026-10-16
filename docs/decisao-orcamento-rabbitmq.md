# Decisão do orçamento: Billing → Execution

O Billing publica `OrcamentoDecidido` (schema 1) na exchange durável `mecanica.events`, routing key `orcamento.decidido.v1`. O Execution consome `execution.orcamento.v1`; eventos inválidos, incompatíveis ou que esgotam as retentativas vão para `execution.orcamento.v1.dlq` via `mecanica.dlx`.

## Comportamento

- Aprovar o orçamento associado ao diagnóstico coloca o reparo em `NA_FILA` e vincula o orçamento. A execução continua sendo iniciada explicitamente pelo operador, com a reserva das peças exigida pelo diagnóstico.
- Recusar coloca o reparo em `CANCELADO`, vincula o orçamento e devolve a reserva, se houver. A devolução ocorre uma única vez.
- O pagamento continua previsto depois do reparo e antes da entrega. Este evento não confirma pagamento.
- Orçamentos criados manualmente pela API não têm vínculo com diagnóstico e não são enviados ao Execution.

## Consistência e falhas

A mudança de status e os dados da outbox `decisao` são gravados no mesmo documento MongoDB, pelo mesmo update condicional. Não depende de transação entre documentos nem de replica set. A correlação `reparoId` é gravada quando o diagnóstico cria o orçamento.

O publicador adquire uma concessão de 30 segundos por documento, usa mensagens persistentes, publicação mandatory e confirmação do RabbitMQ. Só marca como enviado depois do ACK sem retorno por falta de rota. Falhas mantêm o evento para nova tentativa em 30 segundos. Se o processo encerrar, a concessão expira. Uma queda depois da confirmação e antes da marcação pode repetir o envio: a entrega é pelo menos uma vez.

O Execution valida ordem, reparo, presença do diagnóstico e identificador determinístico do orçamento. Bloqueia o reparo e, na mesma transação PostgreSQL, altera seu estado, devolve estoque quando necessário e registra a inbox `decisoes_orcamento_recebidas` (migração V5). Falha em qualquer etapa reverte tudo. Reentregas com a mesma decisão não repetem efeitos nem fazem um reparo em execução voltar à fila. Decisões conflitantes são rejeitadas.

O eventId é o ID do orçamento: nesta versão há uma única decisão final e um único orçamento por diagnóstico. Não há revisão de orçamento. IDs de correlação de cada tentativa de publicação são diferentes.

## Validação local

Na pasta `mecanica-execution-service`:

```powershell
docker compose -f compose.integration.yaml up -d --build
.\scripts\validar-decisoes.ps1
```

O script usa apenas o ambiente isolado nas portas 18082/18083. Cria dados de demonstração, interrompe brevemente apenas seu RabbitMQ e o reinicia em `finally`. Valida aprovação, recusa durante indisponibilidade, reentrega e decisão conflitante na DLQ. Os dados e a mensagem da DLQ ficam disponíveis para inspeção.

## Limites desta etapa

Ainda não é uma Saga completa: faltam integração com OS, pagamento, entrega e compensações dos demais passos. Cancelamento local anterior a uma aprovação provoca uma decisão incompatível na DLQ; exige conciliação, sem reabrir o reparo automaticamente. A DLQ não é descartada nem reenviada automaticamente.

Orçamentos gerados antes desta versão não têm `reparoId` nem outbox da decisão; não são publicados retroativamente. Use novos diagnósticos para validar este fluxo. Uma migração de dados históricos deve reconstruir e conferir os vínculos antes de reenviar decisões, preservando os registros existentes.

Endpoints e credenciais deste Compose são de desenvolvimento local. A autenticação entre serviços e a operação de DLQ/observabilidade em produção ainda estão pendentes.
