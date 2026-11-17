# Validação local — 05/11/2026

## Resultado

| Verificação | Resultado |
|---|---|
| OS — `clean verify` | 111 testes, zero falhas/erros/ignorados; 93,05% de cobertura de linhas; PMD sem violações |
| Billing — `clean verify` com MongoDB local | 156 testes, zero falhas/erros/ignorados; 87,46% de cobertura de linhas; PMD sem violações |
| BDD distribuído — módulo Java/Cucumber | 2 cenários, zero falhas/erros/ignorados |
| Simulador Java do BDD — `pmd:check` | Aprovado, mesmas regras PMD utilizadas pelos serviços |
| Launcher de pagamento integrado | Sintaxe PowerShell validada; execução com credenciais reais ainda pendente |

A suíte principal do Execution não foi alterada nesta etapa. Sua validação anterior permanece registrada nos documentos de 04/11; os 2 cenários acima exercitam adicionalmente o serviço via HTTP e RabbitMQ.

## BDD comprovado

O cenário completo abriu a OS autenticada, aguardou o reparo criado por evento, registrou diagnóstico e reserva, aprovou orçamento, iniciou/concluiu reparo, confirmou cobrança pendente e bloqueio de entrega. Em seguida criou checkout no provedor simulado, rejeitou assinatura inválida, processou webhooks assinados repetidos, confirmou pagamento via consulta HTTP, propagou confirmação pelo broker e entregou a OS. O histórico contém as etapas e apenas um registro PAGAMENTO.

O cenário de recusa cancelou reparo e OS, restituiu o estoque e não criou cobrança. Os três serviços, PostgreSQL, MongoDB e RabbitMQ executaram no Compose isolado. Nenhum cenário forçou estado diretamente no banco.

Execução aprovada às 16:37 de 05/11/2026, horário de São Paulo. Comando usado após construir as imagens atuais:

```powershell
./mvnw.cmd -B -f tests/fluxo-distribuido/pom.xml '-Dbdd.skipBuild=true' verify
```

Para reconstruir tudo, omita `-Dbdd.skipBuild=true`. O perfil isolado usa token fictício e chave RSA temporária. O Compose foi encerrado automaticamente ao final; os volumes foram preservados. O ambiente de integração normal não foi encerrado pelo BDD.

Relatórios no Execution: `tests/fluxo-distribuido/target/cucumber/fluxo.html`, `fluxo.json`, `target/surefire-reports` e `target/pmd.xml`. OS/Billing mantêm seus relatórios JaCoCo, Surefire e PMD em `target`.

## Decisão e limites

Falhas definitivas permanecem pendentes para correção/reprocessamento, com cancelamento explícito pelo operador. Consulte [a política aprovada](politica-falhas-saga.md). Não existe cancelamento automático por timeout.

O histórico da OS e a retirada das responsabilidades de catálogo/estoque/orçamento foram implementados. Tabelas legadas foram preservadas; dados históricos não foram reconstruídos artificialmente.

Continuam necessários: compra real de teste no Mercado Pago e webhook HTTPS, execução dos workflows no GitHub, proteção de branches, implantação/observabilidade e artefatos finais de entrega. Reconciliação de ordens legadas e cancelamento definitivo antes de um reparo existir não foram implementados. Não há evidência de estorno/chargeback nesta etapa.

O BDD com pagamento simulado não comprova integração financeira externa real nem encerra sozinho todos os itens do enunciado.
