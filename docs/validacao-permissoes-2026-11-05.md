# Validação das permissões — 05/11/2026

## Resultados locais

| Serviço | Testes | Cobertura de linhas | PMD |
|---|---:|---:|---|
| OS | 113 | 93,07% | Sem violações |
| Billing, com MongoDB local | 166 | 88,35% | Sem violações |
| Execution, com PostgreSQL local | 96 | 86,38% | Sem violações |

Os três `clean verify` terminaram com BUILD SUCCESS, sem falhas, erros ou testes ignorados. O mínimo JaCoCo de 80% foi preservado. As primeiras tentativas com bancos indisponíveis foram interrompidas; os resultados acima são das execuções concluídas após inicializar Docker e bancos.

O BDD distribuído também terminou com BUILD SUCCESS: **2 cenários aprovados**, com reconstrução das imagens atuais e PMD do simulador aprovado.

## Comportamento comprovado

- Abertura da OS com JWT CLIENTE, usando o vínculo cliente–veículo válido.
- Cliente bloqueado ao listar administrativamente as ordens e ao cadastrar peças; acesso anônimo ao cadastro de peças rejeitado.
- Operador autorizado nas operações internas do Execution e da OS.
- Aprovação/recusa, consulta de orçamento/cobrança e checkout mantidos públicos, por decisão do responsável pelo projeto.
- Webhook sem JWT, com verificação própria de assinatura; assinatura inválida rejeitada no fluxo HTTP real.
- Entrega bloqueada enquanto a cobrança está pendente e permitida após confirmação correlacionada do pagamento.
- Recusa com cancelamento distribuído e restituição do estoque.

Os testes de segurança verificam também administrador, perfil desconhecido, token expirado, emissor incorreto, assinatura de outra chave e ausência de identidade residual entre requisições. A matriz HTTP de Billing/Execution usa a cadeia real do Spring Security com endpoints de teste sem efeitos de negócio; os testes de controllers e o BDD real complementam essa verificação.

## Relatórios e reprodução

Cada serviço mantém Surefire, JaCoCo e PMD em `target`. No Execution, o módulo distribuído mantém `tests/fluxo-distribuido/target/cucumber/fluxo.html`, `fluxo.json` e `target/surefire-reports`.

```powershell
./mvnw.cmd -B -f tests/fluxo-distribuido/pom.xml verify
```

O Compose BDD foi encerrado automaticamente após os cenários; volumes preservados. Os bancos do ambiente de integração local foram utilizados para as suítes dos serviços. As chaves do BDD são temporárias e seu provedor de pagamento é simulado.

Os roteiros PowerShell e o launcher de Billing foram adaptados às chaves/permissões e tiveram a sintaxe conferida. Os antigos roteiros PowerShell não foram todos reexecutados nesta etapa; a comprovação integrada acima é do BDD Java.

## Limites

Esta etapa não comprova emissão de JWT de operador pelo autenticador de produção, publicação no GitHub, deploy AWS nem compra/webhook externo real do Mercado Pago. Esses itens permanecem na validação de entrega. Consulte [matriz de permissões e configuração](seguranca-e-permissoes.md).
