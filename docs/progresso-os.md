# Progresso e valor da OS — validado em 03/11/2026

## Contratos e estados

| Evento | Produtor | Fila da OS | Efeito |
|---|---|---|---|
| DiagnosticoRegistrado v1 | Execution | os.diagnostico.v1 | AGUARDANDO_APROVACAO |
| OrcamentoDecidido v2 | Billing | os.orcamento.v2 | Valor autoritativo + NA_FILA ou CANCELADA |
| ReparoIniciado v1 | Execution | os.reparo-iniciado.v1 | EM_EXECUCAO e início em UTC |
| ReparoConcluido v1 | Execution | os.reparo-concluido.v1 | FINALIZADA e fim em UTC |

O Billing continua publicando a decisão v1 para o Execution. A v2 inclui valorTotal
e moeda. A outbox só confirma publicação após as duas versões serem confirmadas
pelo broker; falha na segunda pode repetir a primeira, cujo consumidor é idempotente.
As duas versões são contratos ativos, não cópias de desenvolvimento. O Billing usa
um único `RabbitDecisaoPublisher` para publicar ambos; os formatos continuam
separados para preservar os consumidores. As fixtures em `src/test/resources/contracts`
são também os exemplos de contrato da documentação.

O início do reparo grava sua outbox na mesma transação da atualização e consumo de reserva.

A migration V6 da OS mantém fatos em progresso_os e recibos de eventos em
eventos_progresso_os. Cada processamento bloqueia a OS e valida seu reparo
associado e o orçamento derivado desse reparo. Duplicatas equivalentes são aceitas;
conteúdo conflitante vai para DLQ. Uma conclusão ou entrega não regride por evento
intermediário atrasado. Aprovação tardia ainda pode completar o valor da OS finalizada.
Recusa incompatível com execução confirmada é rejeitada para revisão.

## Pagamento e entrega

O consumidor financeiro valida o vínculo OS/reparo. Uma confirmação que chegar
antes da projeção da aprovação fica persistida, sem liberar entrega. A entrega exige
recibos correlacionados de aprovação, conclusão e pagamento, igualdade de valor/moeda
e status FINALIZADA. Não há confirmação de pagamento por endpoint manual.

Os testes automatizados usam eventos financeiros sintéticos; não são evidência de
pagamento real no Mercado Pago. A validação integrada realizada não simulou pagamento.

## Como reproduzir

No Execution, usando PowerShell 7.3 ou superior e Docker Desktop:

```powershell
.\scripts\iniciar-integracao-os.ps1
.\scripts\validar-os-execution.ps1
```

O roteiro usa JWT local, cria uma nova massa e verifica os estados intermediários,
valor de R$ 51,00, conclusão, entrega bloqueada e reentrega tardia de início/conclusão.
Os testes ProgressoOrdemJdbcGatewayTest e PagamentoOrdemJdbcGatewayTest aceitam
RUN_OS_POSTGRES_TESTS=true para usar schemas isolados no PostgreSQL local da OS (15434).

## Limites

- Estados antigos não são retroalimentados automaticamente; decisões v1 já publicadas
  antes da criação do consumidor v2 precisam de reconciliação explícita.
- Fluxo financeiro real, compensações completas da Saga e histórico público ainda pendentes.
- Rotas/regras legadas da OS ainda precisam ser removidas ou adaptadas à nova divisão.
- Observabilidade de transições assíncronas e ambiente AWS ainda precisam ser concluídos.
