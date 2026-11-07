# Cancelamento integrado antes do reparo

## Comportamento

`POST /reparos/{id}/cancelar` no Execution cancela reparos em `AGUARDANDO_APROVACAO` ou `NA_FILA`. A chamada repetida sobre um reparo cancelado mantém o resultado. Em `EM_EXECUCAO` ou `CONCLUIDO`, a operação continua bloqueada com HTTP 409.

Execution devolve as peças reservadas e grava o evento de cancelamento na mesma transação PostgreSQL. Se a gravação da outbox ou a devolução falhar, toda a transação é revertida. A migration `V9__outbox_reparos_cancelados.sql` cria essa outbox; não altera migrations antigas nem publica cancelamentos históricos automaticamente.

O evento `ReparoCancelado`, schema 1, usa a routing key `reparo.cancelado.v1`. Cada participante tem sua própria fila e DLQ:

| Consumidor | Fila | Efeito |
|---|---|---|
| OS | `os.reparo-cancelado.v1` | Projeta `CANCELADA`; impede regressão por diagnóstico/aprovação atrasados |
| Billing | `billing.reparo-cancelado.v1` | Projeta orçamento `CANCELADO`; bloqueia novas decisões e criação de cobrança |

O identificador do evento é o UUID do reparo. A primeira ocorrência é preservada na outbox, inclusive quando a requisição HTTP é repetida. As fixtures em `src/test/resources/contracts/reparo-cancelado-v1.json` verificam o contrato entre produtor e consumidores.

## Concorrência e mensagens fora de ordem

- Início e cancelamento disputam a versão do mesmo reparo. Somente uma transação pode confirmar a transição; não se devolvem peças consumidas por um início já confirmado.
- Uma aprovação do Billing que chega após o cancelamento é reconhecida pelo Execution sem reabrir o reparo. Decisões repetidas contraditórias continuam sendo rejeitadas.
- A OS mantém `CANCELADA` ao receber diagnósticos ou decisões tardias. Início/conclusão incompatíveis são rejeitados.
- Se o cancelamento chega antes do diagnóstico, o Billing registra um marcador no documento reservado para aquele orçamento. Não expõe orçamento vazio pela API: a consulta retorna 404 até chegar o diagnóstico. Ao chegar, o diagnóstico completa itens/data e preserva `CANCELADO`.
- Atualizar o estado, registrar o recibo e retirar uma decisão ainda pendente da outbox ocorre atomicamente no mesmo documento MongoDB. Uma decisão já em trânsito pode chegar depois e é tolerada pelos demais participantes.
- Uma aprovação lida antes do cancelamento não consegue sobrescrever o estado cancelado: sua escrita exige orçamento pendente.
- Billing rejeita cancelamento de orçamento que já tem cobrança. OS rejeita cancelamento após execução/conclusão/entrega ou confirmação de pagamento. Esses conflitos exigem análise, não estorno automático.

## Validação

No repositório Execution, com PowerShell 7.3+:

```powershell
.\scripts\iniciar-integracao-os.ps1
.\scripts\validar-cancelamento.ps1
```

O roteiro cria dados novos exclusivamente no Compose local. Exercita cancelamento sem diagnóstico, com orçamento pendente e com orçamento aprovado. Confere estados dos três serviços, liberação da reserva, estoque, ausência de cobrança, reentrega dos envelopes originais e bloqueio de início/aprovação após cancelamento.

O diagnóstico tardio do cenário sem diagnóstico é um evento sintético injetado pelo teste para verificar a ordem de chegada; não representa uma nova operação permitida sobre um reparo cancelado. As reentregas de cancelamento e aprovação usam os envelopes capturados do fluxo real local.

O resultado fica em `%LOCALAPPDATA%\MecanicaDevTools\ultima-validacao-cancelamento.json`, sem credenciais. A fila temporária de captura é removida ao final; as filas de negócio e os dados anteriores são preservados.

## Limites

O fluxo é eventualmente consistente: a resposta do Execution confirma sua transação local; OS e Billing convergem pelo consumo assíncrono. Falhas definitivas permanecem nas DLQs para investigação. Ainda faltam a operação auditável de reprocessamento, a reconciliação de cancelamentos históricos e as regras de estorno/chargeback. Não foi executado pagamento real nem estorno no Mercado Pago.

## Evidência de 04/11/2026

Validação local concluída em `2026-11-04T11:57:05.1283509+00:00`.

| Cenário | OS | Reparo | Orçamento |
|---|---|---|---|
| sem-diagnostico | `f38168ec-d8f2-4634-848b-c28b7c642e53` | `bed113a3-e3e6-49e0-8442-46870c5aa6ee` | `92dd88c4-6c12-32b0-a298-e40601d89aa0` |
| aguardando | `e8abb5ab-4b90-4163-b173-2449f7832b62` | `39a038d7-7f4a-4b85-8c79-c374b039846c` | `5c60a853-073f-3277-a812-814df61d2555` |
| aprovado | `3353ff92-13d2-4288-bfc5-cd79079209a7` | `0220a3ce-3b76-4fc9-a4be-5889374e3b30` | `0f23f845-de0b-3a50-bb80-5534b0302a10` |

Todos permaneceram cancelados após reentregas; estoque final 5 e nenhuma cobrança. Builds completos aprovados: OS 153 testes, Billing 155 e Execution 83; cobertura mínima atendida. Billing validado com MongoDB e Execution com PostgreSQL reais.
