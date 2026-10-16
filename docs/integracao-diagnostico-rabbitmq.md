# Diagnóstico → orçamento com RabbitMQ — 16/10/2026

## Fluxo implementado

1. Execution salva o diagnóstico e sua entrada de outbox na mesma transação PostgreSQL.
2. Um worker publica o evento `DiagnosticoRegistrado`, versão 1, no RabbitMQ.
3. Billing valida o contrato e insere um orçamento AGUARDANDO_APROVACAO no MongoDB.
4. Se a mensagem for repetida, o mesmo orçamento é recuperado sem sobrescrever sua decisão.

Nenhum serviço acessa o banco do outro. O payload contém apenas os identificadores,
data e itens necessários ao orçamento; não leva as observações livres do diagnóstico.

## Garantias e limites

- A outbox contém uma referência ao diagnóstico imutável e seus itens com preços copiados.
  Essa referência é a fonte do payload. Uma falha no diagnóstico também desfaz a outbox.
- O worker usa bloqueio de linha e SKIP LOCKED. Mantém o bloqueio durante a publicação;
  isso simplifica a coordenação, mas limita throughput. Processa um evento por ciclo.
- A publicação é persistente e exige confirmação do broker e ausência de retorno por
  falta de rota. Só então o registro é marcado como enviado. Uma falha aguarda 30 segundos
  antes de nova tentativa; o ciclo normal é de 2 segundos.
- Ainda pode haver repetição se o processo cair após o broker aceitar e antes de confirmar
  a transação SQL. A entrega é pelo menos uma vez; não há garantia de exatamente uma vez.
- Billing usa `_id` determinístico por reparo para a inserção atômica de um único documento.
  A duplicidade consulta os itens originais. Conteúdo incompatível é rejeitado. Isso não
  precisa de uma transação distribuída nem de replica set MongoDB nesta etapa.
- O identificador usa UUID.nameUUIDFromBytes sobre UTF-8 de
  `mecanica:diagnostico:v1:<reparoId>`. É correlação/idempotência, não autenticação.
- Publisher confirm significa que o RabbitMQ aceitou a mensagem, não que Billing concluiu.
  O listener confirma somente após persistir. Falhas transitórias têm duas retentativas;
  mensagens inválidas ou falhas esgotadas vão para a DLQ. A DLQ requer inspeção e reenvio
  após corrigir a causa; ainda não existe reprocessamento administrativo automático.
- O evento tem eventId igual a reparoId porque existe apenas um diagnóstico imutável por
  reparo. A correlação de cada tentativa de publicação é diferente. Revisões futuras
  precisarão de uma nova versão do contrato e outra chave de idempotência.

## Topologia

| Elemento | Nome |
|---|---|
| Exchange direto durável | mecanica.events |
| Routing key | diagnostico.registrado.v1 |
| Fila durável | billing.diagnostico.v1 |
| Exchange de erros | mecanica.dlx |
| Fila de erros | billing.diagnostico.v1.dlq |

Os dois serviços declaram a mesma topologia. Há uma cópia do exemplo de contrato em
`docs/contracts/diagnostico-registrado-v1.json` e uma fixture idêntica nos testes de cada
repositório. O teste do produtor compara seu JSON com a fixture; o consumidor lê a sua.
Não há biblioteca Java compartilhada obrigatória entre os serviços.

## Executar a demonstração

Os repositórios devem ser diretórios irmãos. No mecanica-execution-service:

```powershell
docker compose -f compose.integration.yaml up -d --build --wait
.\scripts\validar-integracao.ps1
docker compose -f compose.integration.yaml down
```

O script cria dados de demonstração, aprova um orçamento, reenvia o evento, interrompe
temporariamente apenas o RabbitMQ deste Compose, testa recuperação e envia uma mensagem
inválida para a DLQ. Não execute o script sobre um ambiente compartilhado. Ele mantém os
dados de exemplo e a mensagem inválida na DLQ para inspeção; `down` preserva os volumes.

| Recurso local | Endereço |
|---|---|
| Execution | http://localhost:18083 |
| Billing | http://localhost:18082 |
| RabbitMQ Management | http://localhost:15673 |
| PostgreSQL de integração | localhost:15435 |
| MongoDB de integração | localhost:17018 |

Credenciais RabbitMQ exclusivamente locais: mecanica_local / mecanica_local_password.
As portas são publicadas somente em loopback. Os volumes e bancos são separados dos
Composes individuais. Autenticação de usuários e hardening de produção continuam pendentes.

Para projetos isolados, a mensageria fica desligada por padrão; habilite com
MESSAGING_ENABLED=true e configure as variáveis RABBITMQ_*. Diagnósticos continuam sendo
salvos na outbox quando o envio está desligado. A migração V4 também inclui diagnósticos
antigos: ao habilitar a mensageria, esses registros pendentes serão enviados.

## Escopo ainda pendente

Este é o primeiro trecho da comunicação distribuída, não a Saga completa. Aprovação,
recusa, cancelamento e conclusão ainda não retornam entre os serviços. Aprovar um orçamento
no Billing ainda não libera automaticamente o reparo. Mercado Pago, bloqueio da entrega,
coordenação da OS e compensações entre serviços seguem pendentes.

Para estudar: comece por PublicadorDiagnostico, DiagnosticoOutboxWorker,
RabbitDiagnosticoPublisher, DiagnosticoListener e ReceberDiagnosticoUseCase. Depois leia
os testes de confirmação, rollback e preservação de um orçamento já aprovado.

## Referências técnicas

- [Confirmações e retornos do Spring AMQP](https://docs.spring.io/spring-amqp/reference/amqp/template.html).
- [Configuração RabbitMQ no Spring Boot 4](https://docs.spring.io/spring-boot/4.0/appendix/application-properties/index.html).
