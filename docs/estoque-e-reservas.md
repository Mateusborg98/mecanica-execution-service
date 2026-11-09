# Catálogo de peças e reservas

## Comportamento implementado

Uma peça tem SKU único, descrição, preço positivo com até duas casas decimais e saldo
disponível. Ela nasce com saldo zero. O preço atual do catálogo não altera orçamentos
que já tenham sido emitidos pelo Billing.

Uma entrada usa `movimentoId` gerado pelo chamador. Repetir o mesmo identificador e
conteúdo não soma novamente. Reutilizar o identificador com dados diferentes retorna 409.
Uma nova entrada deve usar outro identificador.

Cada reparo possui no máximo uma reserva imutável, com uma ou mais peças. A reserva
reduz o disponível. Repetir os mesmos itens, em qualquer ordem, retorna a reserva
existente; quantidades diferentes retornam 409. Peças duplicadas na lista retornam 400.
Todos os itens são reservados na mesma transação: se faltar uma peça ou saldo, nenhuma
alteração é mantida. Reparos inexistentes retornam 404.

Cancelar antes do início libera as quantidades e marca a reserva LIBERADA na mesma
transação do cancelamento. Iniciar marca a reserva CONSUMIDA, sem descontar novamente.
Cancelar depois de iniciar continua proibido. Não existe endpoint de devolução arbitrária.
Repetir a solicitação original depois de cancelar retorna LIBERADA, sem reservar novamente.

Reparos sem reserva ainda podem ser iniciados após aprovação (por exemplo, somente mão
de obra). Quando há diagnóstico, ele define exatamente as peças e quantidades exigidas para iniciar.

## API

| Método | Caminho | Corpo |
|---|---|---|
| POST | /pecas | `{"sku":"FILTRO-01","descricao":"Filtro de óleo","preco":25.50}` |
| GET | /pecas/{id} | — |
| POST | /pecas/{id}/entradas | `{"movimentoId":"UUID","quantidade":10}` |
| POST | /reparos/{id}/reserva | `{"itens":[{"pecaId":"UUID","quantidade":2}]}` |
| GET | /reparos/{id}/reserva | — |

A criação de peça retorna 201 e Location. As demais operações bem-sucedidas retornam 200.
Validação: 400; inexistente: 404; saldo insuficiente, duplicidade incompatível ou estado
inválido: 409. A aplicação continua destinada ao desenvolvimento local, aguardando segurança.

## Por que transações e bloqueios?

O domínio valida os dados. O serviço de aplicação organiza as operações usando contratos.
O adaptador JDBC bloqueia o reparo e as peças em uma ordem estável. O saldo é reduzido
somente se ainda houver quantidade disponível. Isso impede duas OS de reservarem as
mesmas unidades e evita reservas parciais. O cancelamento e a devolução são atômicos.

`Transacao` é um contrato da aplicação; `TransacaoSpring` usa o gerenciador de transações
do mesmo DataSource do JdbcTemplate. A mesma transação engloba reparo e estoque porque
ambos pertencem ao Execution. A comunicação futura com outros serviços exigirá a Saga.

## Estudo e próximos passos

Leia `Peca` e `ItemReserva`, depois `EstoqueService`, `EstoqueJdbcGateway` e
`EstoqueApiTest`. Observe os testes de concorrência, repetição de requisições e rollback.

A migração V2 adiciona as tabelas sem modificar a V1. Para validar H2 e PostgreSQL,
use os comandos de [estado da implementação](estado-implementacao.md).

O catálogo de serviços e o diagnóstico foram acrescentados na etapa de 16/10/2026. Ainda faltam a integração com os itens do orçamento,
mensageria/Saga, segurança e integração Mercado Pago. A reserva não verifica aprovação
ou pagamento; o início do reparo continua exigindo aprovação confirmada pela integração.
