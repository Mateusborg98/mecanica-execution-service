# Execution — estado em 16/10/2026

## Implementado

- Domínio de reparo independente de Spring e banco.
- Serviço de aplicação com criação, consulta, liberação, início, conclusão e cancelamento.
- Persistência JDBC em PostgreSQL próprio, com migração Flyway.
- Um reparo por OS, garantido pela restrição única no banco.
- Atualização condicional pela versão: uma operação concorrente obsoleta recebe 409.
- Datas em UTC com precisão de microssegundos, compatível com PostgreSQL.
- API REST, respostas Problem Details e validação dos identificadores.
- Testes de domínio, API e persistência em H2 e PostgreSQL, incluindo duas threads.
- Dockerfile, Compose e workflow de CI com cobertura mínima de 80%, sem exclusões.

Catálogo de peças, entradas e reservas transacionais também foram implementados.
Consulte [estoque e reservas](estoque-e-reservas.md).

## Contrato REST

| Método | Caminho | Resultado |
|---|---|---|
| POST | /reparos | Cria com body `{"ordemId":"UUID"}`; retorna 201 e Location |
| GET | /reparos/{id} | Consulta o reparo persistido |
| POST | /reparos/{id}/iniciar | Exige estado NA_FILA |
| POST | /reparos/{id}/concluir | Exige estado EM_EXECUCAO |
| POST | /reparos/{id}/cancelar | Permitido apenas antes de iniciar |

Dados inválidos: 400; inexistente: 404; estado incompatível, OS duplicada ou concorrência: 409.

A liberação exige confirmação de aprovação pelo futuro adaptador de integração.
Ela está implementada no serviço de aplicação e testada, mas não está exposta por HTTP.
Assim, enquanto a mensageria não for implementada, um reparo criado pela API permanece
aguardando aprovação e não pode ser iniciado. Não é necessário editar o banco para estudar.

## Executar localmente

```powershell
docker compose --profile app up -d --build --wait
Invoke-RestMethod http://localhost:8083/actuator/health
```

Para os testes rápidos: `.\mvnw.cmd clean verify`.
Para repetir contra PostgreSQL local:

```powershell
docker compose up -d --wait
$env:RUN_POSTGRES_TESTS = "true"
try { .\mvnw.cmd verify } finally { Remove-Item Env:RUN_POSTGRES_TESTS }
```

Cada teste PostgreSQL usa um schema aleatório próprio e remove apenas esse schema.
Para encerrar sem apagar os volumes: `docker compose --profile app down`.

## Pendências

Integração com Billing/OS; revisão de diagnóstico/orçamento; Saga, eventos e compensações;
autenticação/autorização; implantação Kubernetes e observabilidade distribuída.
A API atual é destinada ao desenvolvimento local e está publicada somente em loopback
pelo Compose. A política de acesso será implementada antes da implantação compartilhada.

O fluxo aprovado é orçamento aprovado → reparo → pagamento → entrega.
Concluir um reparo não registra pagamento nem entrega. O bloqueio da entrega pertence à OS.

## Para estudar

Leia na ordem: `domain/reparo/Reparo`, `application/ReparoService`,
`application/ReparoGateway`, `infrastructure/ReparoJdbcGateway`,
`presentation/ReparoController` e `ReparoApiTest`.
A aplicação usa o contrato de persistência; somente o adaptador conhece SQL.
O UPDATE com versão evita que cancelar e iniciar tenham sucesso sobre a mesma versão.

Catálogo de serviços e diagnóstico persistido também estão implementados. Consulte
[serviços e diagnóstico](servicos-e-diagnostico.md), incluindo as regras de compatibilidade com reservas.
