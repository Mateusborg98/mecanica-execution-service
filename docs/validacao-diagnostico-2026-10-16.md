# Validação de serviços e diagnóstico — 16/10/2026

## Executado com sucesso

- Maven verify: 36 testes, zero falhas, zero erros e zero testes ignorados.
- Domínio e integração de API/persistência com H2 em modo PostgreSQL.
- JaCoCo: 309/312 linhas, 99,04%, sem exclusões de classes.
- Catálogo de serviços, diagnóstico misto, total e preservação dos valores originais.
- Repetição concorrente sem duplicidade, rollback para referência inexistente.
- Compatibilidade diagnóstico/reserva nos dois sentidos, início bloqueado sem reserva
  correta e execução de diagnóstico apenas com mão de obra.
- git diff --check sem erros.

## Pendente por ambiente

- Testes desta versão em PostgreSQL real.
- Construção da nova imagem e verificação HTTP no contêiner.
- Aplicação da migração Flyway V3 sobre o banco local V2.

O Docker Desktop falhou na inicialização de seu Inference manager, com erro no socket
local dockerInference. O daemon Linux não ficou disponível. Nenhum contêiner de teste
foi iniciado nesta rodada. Os resultados anteriores em PostgreSQL não validam esta versão.

Após recuperar o Docker:

```powershell
docker compose up -d --wait
$env:RUN_POSTGRES_TESTS = "true"
try { .\mvnw.cmd clean verify } finally { Remove-Item Env:RUN_POSTGRES_TESTS }
docker compose --profile app up -d --build --wait
Invoke-RestMethod http://localhost:8083/actuator/health
docker compose --profile app down
```

A suíte PostgreSQL usa schemas temporários exclusivos. Não apaga dados de negócio.
O CI existente executará os novos testes quando as alterações forem publicadas.
Integração com Billing, mensageria/Saga, revisão de diagnóstico e segurança seguem pendentes.
