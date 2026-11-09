# Validação da decisão do orçamento — 16/10/2026

## Testes locais

- Billing: `clean verify` com MongoDB real, 82 testes, zero falhas/erros/ignorados; cobertura de linhas 294/336 (87,50%).
- Execution: `clean verify` com PostgreSQL real, 54 testes, zero falhas/erros/ignorados; cobertura de linhas 393/423 (92,91%).
- Também passou `clean verify` do Execution no modo H2, sem banco externo.
- Depois foram adicionados dois testes do publicador da outbox com falhas simuladas: perda de acesso ao MongoDB após ACK e falha de confirmação do broker. O Billing passou novamente em `clean verify` sem MongoDB, incluindo esses testes e a exigência de cobertura mínima de 80%; testes que exigem MongoDB são ignorados nesse modo.

Os testes com bancos reais cobrem concorrência na aquisição da outbox, expiração da concessão, retentativas, decisão conflitante, repetição concorrente, preservação de reparo em execução e rollback completo quando a devolução de estoque falha.

## Docker e APIs reais

As duas imagens foram construídas e executadas com `compose.integration.yaml`, usando MongoDB, PostgreSQL e RabbitMQ reais. O script `scripts/validar-decisoes.ps1` concluiu os quatro cenários:

1. Aprovação libera o reparo. Após iniciar, a reentrega preserva EM_EXECUCAO e não consome estoque novamente.
2. Recusa é salva durante indisponibilidade do RabbitMQ. Após recuperação, o reparo é cancelado e a reserva devolvida.
3. Recusa repetida não devolve a reserva novamente.
4. Aprovação conflitante com a recusa é enviada à DLQ sem alterar o reparo cancelado.

Registros locais para inspeção:

- Reparo aprovado/iniciado: `9b019afe-d7f9-4dbb-bb22-2f1abbd68b47`.
- Reparo recusado/cancelado: `bd8042fe-e880-411d-bf34-76293db5218c`.

Após o teste, os contêineres do ambiente integrado foram parados. Volumes e dados foram preservados. A adaptação do cálculo do UUID para o .NET do Windows PowerShell foi validada contra o contrato JSON compartilhado; a sintaxe do script também foi validada pelo parser do PowerShell.

## Limites

Validação local da etapa diagnóstico → orçamento → decisão → reparo. Não demonstra a Saga completa, pagamento Mercado Pago ou entrega. Orçamentos históricos sem correlação de reparo não foram migrados; o novo fluxo deve ser validado com novos diagnósticos. Consulte `decisao-orcamento-rabbitmq.md` para os detalhes e limites de operação.
