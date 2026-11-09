# Análise estática e critérios de aprovação

O projeto usa PMD pelo `maven-pmd-plugin` 3.28.0 (motor PMD 7.17.0), Java 21 e Maven. Não exige servidor, conta ou credenciais. O arquivo versionado `config/pmd/ruleset.xml` define o mesmo conjunto de 12 regras nos três serviços e nas duas ferramentas Java auxiliares.

## O que é verificado

- Comparações incorretas de objetos, strings e null.
- Verificações de null inconsistentes e condições incondicionais.
- Retorno dentro de finally, que pode ocultar exceções.
- Blocos de controle vazios e exceções ignoradas sem tratamento explícito.
- Variáveis locais, campos privados e imports sem uso.

O conjunto é deliberadamente focado em defeitos e código sem uso. Não impõe um guia de formatação nem equivale a todas as regras do PMD ou do SonarQube. Analisa `src/main/java`; testes são executados pelo JUnit, mas não são analisados por este conjunto PMD. Não há supressão global, baseline de violações ou tolerância de erros: qualquer violação das regras selecionadas bloqueia a fase `verify`.

## Execução local e CI

Na raiz de cada serviço:

```powershell
.\mvnw.cmd clean verify
```

Esse comando executa os testes, verifica a cobertura JaCoCo e exige zero violações PMD. Para somente analisar o fonte, sem apresentar isso como evidência de testes:

```powershell
.\mvnw.cmd -DskipTests compile pmd:check
```

As ferramentas auxiliares têm builds separados, executados também nos respectivos pipelines:

```powershell
# Na raiz do Execution
.\mvnw.cmd -f tools/dlq/pom.xml clean verify
# Na raiz do Billing
.\mvnw.cmd -f tools/webhook-proxy/pom.xml clean verify
```

Relatórios PMD: `target/pmd.xml` e `target/reports/pmd.html` em cada projeto. Os pipelines em `.github/workflows/ci.yml` publicam esses arquivos junto dos relatórios JUnit e JaCoCo, inclusive quando o build falha. No Execution, também publicam os cenários Cucumber.

A OS agora possui CI próprio com PostgreSQL 16, testes, cobertura, PMD e build Docker. Billing mantém MongoDB nos testes; Execution mantém PostgreSQL. Os workflows validam pull requests e pushes para main/codex/**. As proteções de branch que tornam esses checks obrigatórios ainda precisam ser conferidas no GitHub; o YAML sozinho não impede merge.

## Correção identificada

O filtro JWT da OS ignorava exceções em um catch vazio. Agora limpa explicitamente o contexto de autenticação antes de continuar a cadeia de filtros. Um teste de regressão verifica token inválido com contexto previamente preenchido, ausência de autenticação ao prosseguir e continuidade da cadeia. Não são registrados tokens ou detalhes sensíveis.

## Limites

PMD não substitui os testes de negócio, a cobertura, revisão humana, análise de dependências vulneráveis nem validação da infraestrutura. Não foi criado servidor SonarQube. Alterações e validações locais não comprovam execução remota dos pipelines, proteção da main ou deploy.

Referências: [Maven PMD Plugin](https://maven.apache.org/plugins/maven-pmd-plugin/) e [regras Java do PMD](https://pmd.github.io/pmd/pmd_rules_java.html).
## Evidência local de 04/11/2026

| Projeto | Testes aprovados | PMD |
|---|---:|---|
| OS | 154 | Zero violações e erros de análise |
| Billing | 155 | Zero violações e erros de análise |
| Execution | 86 | Zero violações e erros de análise |
| Ferramenta DLQ | 16 | Zero violações e erros de análise |
| Proxy webhook | 7 | Zero violações e erros de análise |

Total: 418 testes, sem falhas ou testes ignorados. Builds dos serviços executados com `clean verify`, cobertura mínima atendida, MongoDB no Billing e PostgreSQL nos testes habilitados de OS/Execution. As ferramentas passaram em `verify`.

O critério de bloqueio também foi demonstrado: uma classe temporária com `equals(null)` provocou falha por EqualsNull. Após removê-la, a análise voltou a passar. A classe de prova não permanece no repositório. Os cinco relatórios XML foram conferidos sem violações/erros, com HTML gerado.

A primeira limpeza de target da OS foi bloqueada pelo Windows; a repetição de `clean verify` concluiu com sucesso. Nenhuma supressão foi adicionada para contornar esse problema. Execução dos workflows no GitHub, build Docker pelo novo workflow e regras de proteção da branch não foram validados nesta etapa.
