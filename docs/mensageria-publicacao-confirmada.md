# Publicação confirmada de eventos

Billing e Execution possuem um componente interno `PublicadorRabbitConfirmado`, reutilizado pelos publishers do próprio serviço. Ele centraliza JSON, metadados AMQP, envio e espera pela confirmação do broker.

Os publishers continuam responsáveis por selecionar a rota e montar o contrato de seu evento. Diagnóstico, início, conclusão, cancelamento, decisão e pagamento mantêm seus envelopes, identificadores e versões. Nenhuma regra de negócio foi transferida para o componente de transporte.

## Garantias preservadas

- Conteúdo JSON com codificação UTF-8 e mensagem persistente.
- `messageId` igual ao identificador do evento original.
- Correlação nova a cada tentativa de envio, inclusive quando o evento é repetido.
- Espera máxima de cinco segundos pela confirmação.
- Rejeição, mensagem devolvida por falta de destino, timeout e erro na confirmação não são tratados como sucesso.
- Interrupção preserva o sinal da thread para permitir o encerramento do worker.

Confirmar a publicação significa que o broker aceitou a mensagem; não significa que o consumidor concluiu sua transação. Outbox, repetição e idempotência continuam necessárias. O worker mantém a responsabilidade por reagendar uma publicação que falhou.

## Organização

O componente é interno ao pacote de mensageria e existe em cada repositório para preservar builds e versões independentes. Não foi criada uma biblioteca compartilhada entre microsserviços. A OS possui um único publisher e não recebeu uma abstração adicional nesta etapa.

## Verificação

Os testes existentes de cada publisher continuam comparando o JSON com as fixtures versionadas e verificando rota, persistência, identificador, rejeição e mensagem sem destino. Os testes do componente acrescentam timeout, interrupção e correlação independente no reenvio.

Para executar a suíte e os critérios de qualidade, use `mvnw.cmd clean verify`. A execução com bancos reais segue as variáveis e instruções documentadas em cada serviço.

## Próxima melhoria separada

Os workers PostgreSQL do Execution ainda publicam enquanto mantêm uma transação aberta. A extração do transporte não muda esse comportamento. Reservar eventos em transação curta, publicar fora dela e confirmar com uma lease é uma alteração distinta, que exige testes de concorrência e recuperação após interrupção.

## Evidência local — 06/11/2026

clean verify: BUILD SUCCESS; 99 testes, sem falhas, erros ou testes ignorados. Cobertura de linhas: 87.43%. PMD: zero violações. Integração com banco real habilitada. A suíte distribuída entre os três serviços não foi reexecutada nesta refatoração.
