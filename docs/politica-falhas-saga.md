# Decisão de tratamento das falhas da Saga — 05/11/2026

Decisão confirmada pelo responsável pelo projeto: **manter pendente para correção e reprocessamento, com cancelamento explícito pelo operador**. Uma falha técnica não representa recusa do cliente nem autorização para cancelar atendimento.

## Regra operacional

1. Falha temporária de banco, broker ou provedor: preservar o estado confirmado, a outbox/inbox e os identificadores originais. Os mecanismos existentes repetem a operação; não emitir uma decisão de negócio falsa.
2. Mensagem inválida ou contraditória: isolar na DLQ e investigar causa/correlação. Não converter aprovação em recusa nem alterar o envelope para fazê-lo passar.
3. Solicitação de reparo ou diagnóstico sem processamento definitivo: manter a OS no último estado confirmado. `publicado=true` na solicitação significa confirmação do broker, não criação do reparo.
4. Corrigir a causa e reprocessar somente o evento original validado, usando a ferramenta Java auditável de DLQ. A confirmação de publicação não encerra a investigação: verificar a convergência pelos endpoints dos participantes.
5. Se a decisão for cancelar, o operador usa `POST /reparos/{reparoId}/cancelar`, antes do início. Execution libera reservas e publica cancelamento; OS e Billing convergem de forma idempotente. Depois do início, o endpoint rejeita cancelamento e não devolve automaticamente peças consumidas.

## Conferência por etapa

| Situação | Conferência antes de encerrar a ocorrência |
|---|---|
| Reparo não criado | Consultar a solicitação na OS, localizar a mensagem correlacionada e corrigir/reprocessar; confirmar o reparo pela API Execution |
| Orçamento não criado | Preservar diagnóstico e preços; corrigir/reprocessar diagnóstico; confirmar orçamento no Billing |
| Decisão não propagada | Confirmar estado no Billing, publicação e processamento; verificar reparo e OS; nunca emitir outra decisão contraditória |
| Conclusão não propagada | Preservar conclusão física; recuperar publicação/consumo até existir cobrança e OS FINALIZADA |
| Pagamento não confirmado | Consultar provedor e correlação; manter entrega bloqueada enquanto não houver confirmação válida |
| Cancelamento solicitado | Confirmar reparo CANCELADO, reserva LIBERADA, estoque restituído uma vez e OS CANCELADA |

## Limites explícitos

Não há cancelamento automático por timeout nem evento de falha que transforme a OS em cancelada. Não há painel central de incidentes; a operação usa estados das APIs, logs, outboxes/inboxes e DLQ. A ferramenta de reprocessamento atual é local; autorização/TLS/auditoria central para produção continuam necessários.

Quando o reparo ainda não existe, o endpoint de cancelamento do Execution não pode ser chamado como se ele existisse. Primeiro é necessário corrigir a criação e confirmar o reparo, então solicitar seu cancelamento. Cancelar definitivamente uma OS antes de qualquer reparo existir exigiria um contrato adicional de cancelamento com proteção contra uma solicitação tardia; isso não está implementado nem é simulado como concluído.

Ordens legadas não ganham vínculos ou eventos inventados. A migração do histórico registra somente o marco do estado conhecido. Reconciliação de dados antigos exige revisão específica.

Consulte [Saga](saga.md), [cancelamento](cancelamento-integrado.md) e [reprocessamento auditável](reprocessamento-dlq.md).
