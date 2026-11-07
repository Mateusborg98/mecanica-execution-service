# Abertura da OS e solicitação de reparo

A criação da OS grava também uma solicitação na mesma transação. O PostgreSQL da
OS mantém `ordem_id`, `reparo_id` e o evento pendente na tabela `solicitacoes_reparo`.
O adaptador executa flush do JPA antes da gravação JDBC; ambos usam a mesma transação.

Com `MESSAGING_ENABLED=true`, o worker publica `ReparoSolicitado` v1 no exchange
direto `mecanica.events`, rota `reparo.solicitado.v1`. Há confirmação do broker,
mensagem persistente e verificação de mensagem devolvida por ausência de rota.
Falhas reagendam a publicação em 30 segundos; lease de 30 segundos permite recuperação
após reinício. Uma falha entre ACK e gravação local pode repetir a mensagem.

O Execution consome `execution.reparo-solicitado.v1`. Inbox e reparo são gravados
na mesma transação em seu próprio banco. Duplicatas não alteram um reparo já iniciado;
conteúdo conflitante é rejeitado. Erros transitórios usam o retry configurado, seguido
da DLQ `execution.reparo-solicitado.v1.dlq`. Corrija a causa antes de republicar a mensagem original.

## Consulta do vínculo

`GET /ordens-servico/{id}/reparo` retorna `ordemId`, `reparoId` e `publicado`.
A rota segue a política de autenticação da OS. `publicado=true` significa confirmação
do broker, não confirmação de processamento pelo Execution. Consulte
`GET /reparos/{reparoId}` no Execution para confirmar a criação.

Os IDs são definidos na abertura e mantidos nas reentregas. O evento não contém
dados pessoais nem credenciais. Nenhum serviço consulta diretamente o banco do outro.

## Limites desta etapa

- Somente novas OS geram solicitação; não há migração automática das OS antigas.
- Um reparo manual já existente para a mesma OS gera conflito e precisa de revisão.
- A OS ainda tem itens e estoque herdados. Eles NÃO são copiados para o Execution;
  diagnóstico/reserva continuam realizados no Execution. A remoção dessas responsabilidades
  da OS e a projeção do valor aprovado ainda precisam ser concluídas.
- A atualização automática de status da OS, a confirmação de recebimento do reparo,
  as compensações da Saga e o fluxo completo com Mercado Pago continuam pendentes.
- O Compose integrado inclui a OS no profile `os`; consulte `integracao-os-conclusao.md`.

## Validação em 27/10/2026

OS: clean verify aprovado, 124 testes; inclui commit/rollback JPA + outbox JDBC, leases e confirmação de publicação. Execution: verify aprovado, 71 testes, repetidos com PostgreSQL real em schemas isolados; inclui inbox/reparo atômicos e reentregas concorrentes. Ambos passaram a cobertura mínima de 80%. A etapa posterior de validação entre processos está descrita em `integracao-os-conclusao.md`.
