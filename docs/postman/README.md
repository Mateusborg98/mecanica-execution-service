# Collection das operações principais — Fase 4

Importe `fase4.postman_collection.json` no Postman. Configure `baseUrl`, JWT e identificadores em um ambiente local; não exporte tokens. OS usa 18081; Execution 18083; Billing iniciado pelo roteiro de checkout usa 8082 (Compose integrado usa 18082).

Esta collection é manual e cobre o atendimento distribuído; não é um runner de cenários nem substitui todos os contratos de cadastro. Na OS, cliente, operador e veículo devem existir antes da abertura. O Swagger existente da OS complementa os cadastros. No Billing, o orçamento do fluxo integrado nasce do diagnóstico via RabbitMQ.

1. Abra a OS e obtenha `ordemId`; consulte o vínculo `/reparo` para `reparoId`.
2. No Execution, crie serviço com código único e copie `servicoId`; registre diagnóstico. Para testar estoque, inclua também a peça no diagnóstico antes de reservar.
3. Aguarde o processamento assíncrono e consulte o reparo para obter `orcamentoId`.
4. No Billing, consulte/envie o orçamento. Aprovar e recusar são caminhos alternativos: use registros diferentes.
5. Após aprovação, consulte a fila, inicie e conclua no Execution. Consulte cobrança, gere checkout e complete o pagamento de teste externamente.
6. Na OS, entrega anterior ao pagamento deve falhar; após confirmação válida, deve ser permitida.

Chamadas sem corpo não exigem JSON vazio. O webhook real exige assinatura e não está representado por uma chamada falsa. Não há operação manual de marcar pagamento como aprovado. SMTP e compra externa permanecem pendentes; importar a collection não executa nem comprova os testes.

Campos vazios são intencionais: preencha IDs com respostas reais. Use códigos/SKUs únicos. Cancelamento só antes do início; não executar cancelamento e fluxo feliz no mesmo reparo. O JWT deve corresponder às permissões administrativas documentadas.