# Saga da oficina: decisões, falhas e compensação

## Estratégia

A integração atual usa **Saga por coreografia**: cada serviço confirma sua transação local e publica eventos para os participantes interessados. Não há coordenador central nem transação distribuída entre os bancos. A escolha acompanha a divisão de responsabilidades: OS acompanha o atendimento, Execution executa o reparo e controla estoque, Billing decide orçamento e controla cobrança/pagamento.

Outbox preserva eventos junto com a mudança local; confirmação do RabbitMQ permite marcar a publicação. Os consumidores persistem sua decisão e o efeito na mesma transação local quando aplicável. A entrega é pelo menos uma vez: consumidores precisam reconhecer duplicatas. Confirmação de publicação significa aceitação pelo broker, não conclusão do processo inteiro.

## Etapas e falhas

| Etapa | Responsável e efeito | Recuperação técnica | Compensação / limite de negócio |
|---|---|---|---|
| Abrir OS | OS salva atendimento e solicitação de reparo | Outbox reenvia; Execution reconhece solicitação duplicada | Rejeição definitiva na criação ainda exige reconciliação; não há evento de falha retornando à OS |
| Diagnosticar | Execution preserva itens/preços e publica diagnóstico; Billing cria orçamento | Outbox e criação idempotente do orçamento | Diagnóstico não reserva automaticamente; a reserva tem operação própria |
| Reservar peças | Execution reduz disponibilidade e registra reserva | Transação local evita reserva parcial | Recusa do orçamento libera a reserva e devolve disponibilidade |
| Aprovar orçamento | Billing grava decisão e publica v1 para Execution e v2 para OS | Se uma publicação falhar, repete; duplicatas são reconhecidas | Reparo vai para NA_FILA; aprovação não significa pagamento |
| Recusar orçamento | Billing fica RECUSADO; Execution cancela reparo/libera reserva; OS fica CANCELADA | Outbox retoma após indisponibilidade; inbox impede repetição de efeito | Compensação implementada: disponibilidade retorna ao valor anterior à reserva |
| Iniciar reparo | Execution consome reserva e publica início | Reserva incompatível faz rollback local; evento segue por outbox | Não se deve devolver automaticamente peças já consumidas |
| Concluir reparo | Execution conclui; Billing cria cobrança PENDENTE; OS fica FINALIZADA | Outbox e consumidores idempotentes | Reparo físico concluído não pode ser desfeito por rollback; falha técnica deve ser recuperada |
| Receber pagamento | Billing valida provedor e confirma pagamento; OS recebe confirmação | Inbox do webhook, consulta ao provedor e outbox de confirmação | Estorno/chargeback ainda não implementados; pagamento real ponta a ponta pendente |
| Entregar veículo | OS exige conclusão e pagamento correlacionado | Transação local protege a entrega | Cobrança pendente bloqueia entrega; conclusão do reparo sozinha não libera o veículo |

Rollback local e compensação são distintos. Se a transação de reserva falha, o próprio PostgreSQL desfaz as alterações locais. Se o orçamento é recusado depois de a reserva ter sido confirmada, uma nova transação de negócio libera essa reserva: essa é a compensação.

## Garantias da recusa

O consumidor do Execution bloqueia o reparo, valida OS, diagnóstico e orçamento e consulta a decisão já recebida. Cancelamento, liberação das peças e registro da decisão são confirmados juntos. Uma repetição equivalente não repete a devolução. Uma aprovação com o mesmo identificador de uma recusa é conflito e deve ir para a DLQ.

A projeção da OS recebe a decisão v2, com valor e moeda, de modo independente. Durante uma indisponibilidade, os serviços podem apresentar estados diferentes temporariamente. A recuperação deve fazer os estados convergirem; não se deve fingir uma compensação completa enquanto algum participante continua pendente.

## Roteiro reproduzível

No repositório `mecanica-execution-service`, com PowerShell 7.3 ou superior:

```powershell
.\scripts\iniciar-integracao-os.ps1
.\scripts\validar-saga-recusa.ps1
```

O roteiro usa exclusivamente o Compose local nas portas 18081, 18082, 18083 e 15673. Cria OS autenticada, reparo, peça e orçamento novos. Para o RabbitMQ temporariamente e o restaura em `finally`. Pode interromper outros testes em execução nesse mesmo ambiente; execute-o isoladamente.

As verificações são:

1. Estoque começa em 5 e fica em 3 após reservar duas peças.
2. Billing aceita e persiste a recusa com RabbitMQ indisponível.
3. Após restaurar o broker, Execution fica CANCELADO, reserva LIBERADA, OS CANCELADA e estoque volta a 5; cobrança continua inexistente.
4. Reenvio dos envelopes originais v1 e v2 é confirmado pelos consumidores e preserva os resultados.
5. Uma aprovação conflitante vai para `execution.orcamento.v1.dlq` sem reabrir o reparo nem alterar estoque.

Uma fila temporária captura os envelopes originais para a reentrega e é removida ao final. A mensagem conflitante permanece na DLQ como evidência; o teste não limpa filas de negócio nem mensagens anteriores. O resultado sem credenciais é salvo em `%LOCALAPPDATA%\MecanicaDevTools\ultima-validacao-saga-recusa.json`.

## Tratamento da DLQ

A DLQ demanda diagnóstico, não reenvio automático indiscriminado. Decisão contraditória não deve ser transformada em aprovação: preserve o estado já confirmado e investigue a origem. Para falha recuperável, corrija primeiro a causa e reenvie o envelope original com o mesmo identificador, após validar sua correlação. O procedimento local de seleção, auditoria e reprocessamento foi implementado e validado; consulte [operação e limites](reprocessamento-dlq.md).

## Pendências para fechar a Saga da entrega

- Retorno de falhas definitivas na abertura/diagnóstico e reconciliação de ordens antigas.
- Cancelamento direto antes do início implementado nos três serviços; ver [contrato e validação](cancelamento-integrado.md). A reconciliação de cancelamentos históricos permanece pendente.
- Cancelamento após aprovação é permitido somente antes do início; execução ou pagamento continuam bloqueando cancelamento. Estorno/chargeback permanecem pendentes e não podem ser simulados como concluídos.
- Adaptar a operação auditável local da DLQ para produção: autorização, TLS e auditoria central durável. A validação local está em [reprocessamento](reprocessamento-dlq.md).
- Pagamento real de teste, webhook e entrega autorizada.

Este documento comprova apenas o que os testes e as evidências registradas sustentam. A validação da recusa não representa a conclusão de toda a Saga nem do fluxo financeiro.

## Evidência local de recusa

Validação concluída em `2026-11-03T23:17:30.8805008+00:00` pelo roteiro `validar-saga-recusa.ps1`.

- OS: `8384e29d-2bb6-42ba-9734-d69cc49f3c58` — CANCELADA.
- Reparo: `79e8d6aa-5289-4cbd-81f9-122b719987fa` — CANCELADO.
- Orçamento: `e0391fb2-3b48-3046-b588-c2b499252e31` — RECUSADO.
- Reserva LIBERADA; disponibilidade de peças: 5 → 3 → 5.
- Recusa persistida com broker indisponível e recuperada pela outbox.
- Reentrega dos envelopes originais confirmada sem duplicar compensação.
- Aprovação conflitante isolada na DLQ; nenhum pagamento real executado.

## Decisão operacional de 05/11/2026

Falhas definitivas mantêm o atendimento pendente para correção e reprocessamento; cancelamento exige ação explícita. Consulte [regra aprovada, roteiro e limites](politica-falhas-saga.md).
