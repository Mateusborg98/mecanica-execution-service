# Catálogo de serviços e diagnóstico — 16/10/2026

## Implementação

O catálogo registra código único, descrição e preço positivo de cada serviço. Peças
continuam no catálogo próprio. Um diagnóstico reúne referências aos dois catálogos,
quantidades e observações do reparo. Os preços e descrições são obtidos pelo servidor;
o cliente não informa valores no diagnóstico.

O diagnóstico grava uma cópia dos valores e descrições no momento de sua criação.
Consultas e reenvios usam essa cópia, mesmo que o catálogo seja alterado posteriormente.
O total usa BigDecimal e soma quantidade × valor unitário. A criação ocorre em uma
transação: referência inexistente desfaz todos os itens e o cabeçalho.

Nesta etapa há um diagnóstico imutável por reparo, registrado antes da aprovação.
Repetir observações e itens idênticos, mesmo em outra ordem, retorna o diagnóstico
existente. Conteúdo diferente retorna 409. Revisão de diagnóstico/orçamento ainda não
foi implementada; não altere as tabelas manualmente para mudar um diagnóstico real.

## API

| Método | Caminho | Resultado |
|---|---|---|
| POST | /servicos | Cadastra serviço; 201 e Location |
| GET | /servicos/{id} | Consulta serviço; 200 |
| POST | /reparos/{id}/diagnostico | Registra ou recupera repetição idêntica; 200 |
| GET | /reparos/{id}/diagnostico | Consulta itens, referências, datas e valorTotal; 200 |

Criar serviço:

```json
{"codigo":"TROCA-FILTRO","descricao":"Troca do filtro de óleo","preco":100.00}
```

Registrar diagnóstico (substitua as referências por UUIDs retornados pelos cadastros):

```json
{
  "observacoes": "Filtro com desgaste; substituir e revisar a vedação.",
  "itens": [
    {"tipo":"PECA","referenciaId":"UUID-DA-PECA","quantidade":2},
    {"tipo":"SERVICO","referenciaId":"UUID-DO-SERVICO","quantidade":1}
  ]
}
```

Validação: 400; referência inexistente: 404; código duplicado, estado incompatível ou
diagnóstico já registrado com outro conteúdo: 409.

## Integração com reservas

Se houver diagnóstico, a reserva precisa conter exatamente suas peças e quantidades.
Uma tentativa divergente retorna 409 sem descontar saldo; o cliente pode corrigir a
requisição e tentar novamente. Se já houver reserva, um novo diagnóstico incompatível
também será recusado sem ser salvo.

Ao iniciar, as quantidades são novamente conferidas na mesma transação que muda o
estado do reparo e consome a reserva. Se faltar reserva, o reparo permanece NA_FILA.
Um diagnóstico somente com mão de obra dispensa reserva. Reparos antigos sem diagnóstico
mantêm o comportamento anterior até a integração definitiva com o fluxo de OS/Billing.

## Limite desta etapa

O diagnóstico agora gera uma entrada de outbox e, com mensageria habilitada, publica
o evento que cria o orçamento no Billing. Veja [a integração RabbitMQ](integracao-diagnostico-rabbitmq.md). A aprovação não tem endpoint manual e continua aguardando a integração.
Mensageria/Saga, revisão de diagnóstico, segurança e pagamento são etapas posteriores.

## Para estudar

Leia `Servico`, `Diagnostico`, `DiagnosticoService`, `DiagnosticoJdbcGateway` e
`DiagnosticoApiTest`. Observe a diferença entre referência de catálogo e cópia do valor,
e entre repetir uma requisição e alterar um registro já aceito.

A migração V3 acrescenta tabelas sem reescrever V1/V2. Os comandos de build e execução
seguem em [estado da implementação](estado-implementacao.md).
