# Política de falhas da Saga

A Saga é coreografada: cada serviço confirma sua transação local e publica eventos por outbox. Isso preserva independência dos participantes e permite repetir mensagens sem exigir transação distribuída entre PostgreSQL, MongoDB e RabbitMQ.

A decisão de negócio é manter falhas definitivas pendentes para correção/reprocessamento, com cancelamento explícito pelo operador antes da execução. Falha técnica não equivale à recusa do cliente.

| Etapa / falha | Recuperação ou compensação |
| --- | --- |
| Transação local não confirmada | Rollback local; não publicar evento de sucesso |
| Broker indisponível | Preservar outbox e repetir com os mesmos identificadores |
| Mensagem inválida/contraditória | Isolar em DLQ, corrigir causa e reprocessar o evento original de forma auditável |
| Reparo ainda não criado | Corrigir/reprocessar solicitação, ou cancelar explicitamente usando os IDs reservados pela OS; marcador durável impede criação tardia de reabrir atendimento |
| Orçamento não criado | Recuperar diagnóstico preservando preços; alternativamente cancelar antes do início. Billing preserva cancelamento mesmo se diagnóstico chegar depois |
| Orçamento recusado / cancelamento | Cancelar reparo e orçamento, liberar reservas uma única vez e projetar OS CANCELADA |
| Decisão não propagada | Recuperar publicação/consumo sem emitir decisão contraditória |
| Reparo já iniciado ou concluído | Recuperação para frente; preservar execução física e recuperar eventos até cobrança/OS convergirem. Cancelamento anterior à execução não se aplica |
| Pagamento não confirmado | Consultar provedor e correlação; manter entrega bloqueada até confirmação válida |
| SMTP indisponível | Manter pedido persistido e repetir. Falha no e-mail não aprova, recusa nem cancela orçamento |

## Cancelamento anterior à criação

Consulte `GET /ordens-servico/{ordemId}/reparo` na OS com JWT administrativo. Use exatamente os campos `reparoId` e `ordemId` retornados em `POST /reparos/cancelamentos` no Execution. Não invente identificadores. O Execution grava um registro CANCELADO mesmo se a solicitação de criação ainda estiver na outbox da OS. A solicitação tardia é reconhecida sem reabrir o reparo.

Se já existe reparo, permanece disponível `POST /reparos/{reparoId}/cancelar`. Ambos exigem OPERADOR ou ADMINISTRADOR, rejeitam reparos iniciados/concluídos e preservam a compensação de reservas e o evento de cancelamento na mesma transação local.

`publicado=true` na OS indica confirmação do broker, não comprova processamento pelo Execution. Após qualquer recuperação, confira estados dos participantes e histórico da OS.

## Limites

Não existe rollback físico de um reparo nem transação global entre serviços. Não há cancelamento automático por timeout, estorno/chargeback automático ou reconciliação de ordens legadas. Falhas após execução exigem recuperação para frente e, quando houver conflito de negócio, intervenção operacional. Se a avaliação exigir compensação financeira após pagamento, essa extensão precisa ser implementada; não está sendo apresentada como pronta.

O reprocessamento auditável existe como ferramenta Java local; controles de operação em produção continuam necessários. SMTP admite duplicidade na janela entre aceitação remota e confirmação local. Consulte [cancelamento](cancelamento-integrado.md), [Saga](saga.md) e [DLQ](reprocessamento-dlq.md).