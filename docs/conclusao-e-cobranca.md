# Conclusão do reparo e cobrança pendente

## Fluxo implementado em 20/10/2026

1. O operador conclui um reparo EM_EXECUCAO com `POST /reparos/{id}/concluir`.
2. O Execution grava CONCLUIDO e a outbox da conclusão na mesma transação PostgreSQL.
3. O evento `ReparoConcluido` é publicado na exchange `mecanica.events`, routing key `reparo.concluido.v1`.
4. O Billing consome `billing.reparo-concluido.v1`, valida a correlação e o orçamento APROVADO e registra uma cobrança PENDENTE no próprio documento do orçamento.
5. `GET /cobrancas/{orcamentoId}` retorna a cobrança; antes de sua criação, retorna 404.

A cobrança é um registro interno. Não há pedido enviado ao Mercado Pago, cobrança real, link de pagamento nem confirmação de pagamento. Não existe endpoint para marcar manualmente como pago. A entrega continua pendente de integração com a OS e confirmação real de pagamento.

## Valores e correlação

O evento contém schemaVersion, eventType, eventId, reparoId, ordemId, orcamentoId e ocorridoEm. Não transporta valor financeiro: o Billing calcula o total dos itens do orçamento aprovado que já possui. A moeda desta etapa é BRL.

O eventId é o reparoId; há uma única conclusão por reparo. O orcamentoId precisa corresponder ao ID determinístico gerado a partir do diagnóstico. Ordens, reparos e orçamento precisam coincidir, e a conclusão não pode anteceder o diagnóstico. Datas no Billing são normalizadas para milissegundos, precisão do MongoDB.

A cobrança incorpora o evento recebido, seu instante, o valor e o estado PENDENTE em um único update condicional MongoDB. O ID público da cobrança é o ID do orçamento. Mensagens repetidas retornam o registro existente sem mudar o valor, o estado nem a data de criação. Conteúdo conflitante é rejeitado, inclusive se duas entregas ocorrerem simultaneamente.

## Recuperação de falhas

- A migração V6 cria `outbox_reparos_concluidos`. Os eventos são gravados mesmo com a mensageria desligada ou o broker offline.
- O publicador usa mensagem persistente, mandatory e confirmação do broker. Mantém o evento pendente em caso de falha e tenta novamente após 30 segundos.
- Publicadores concorrentes usam `FOR UPDATE SKIP LOCKED`. Uma queda após o ACK e antes do commit pode repetir o evento: o consumidor é idempotente.
- Payloads inválidos, correlações incompatíveis e mensagens que esgotam retentativas vão para `billing.reparo-concluido.v1.dlq`, via `mecanica.dlx`.
- Concluir antes de iniciar continua proibido; repetir a chamada de conclusão retorna conflito, sem criar outra outbox.
- Reparos históricos já concluídos não são publicados automaticamente pela V6. A correlação com orçamentos anteriores precisa ser reconciliada antes de qualquer reprocessamento histórico.

## Executar a demonstração

Na pasta `mecanica-execution-service`:

```powershell
docker compose -f compose.integration.yaml up -d --build
.\scripts\validar-conclusao-cobranca.ps1
```

O script usa somente o ambiente integrado isolado (Execution 18083, Billing 18082). Cria dados, aprova/inicia/conclui um reparo, interrompe brevemente seu RabbitMQ e o reinicia em `finally`. Verifica ausência de cobrança antes da conclusão, recuperação da outbox, idempotência e DLQ. Mantém dados e mensagens de demonstração para inspeção. Nenhuma credencial do Mercado Pago é necessária.

## Próximas etapas

Integração da OS com os serviços, solicitação de pagamento ao provedor, tratamento autenticado das notificações e confirmação do pagamento antes de autorizar a entrega. A escolha do produto/API Mercado Pago e as credenciais de teste ainda estão pendentes; nenhum adaptador fictício é apresentado como uma integração real.
