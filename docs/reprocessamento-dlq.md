# Reprocessamento auditável de DLQ

Operação validada no ambiente Docker local em 04/11/2026. A ferramenta está em `mecanica-execution-service/tools/dlq`; os três serviços usam o mesmo procedimento. Não configura operação em produção.

## Preparação e uso

No PowerShell, a partir da raiz de mecanica-execution-service, com o ambiente de integração ativo:

```powershell
.\mvnw.cmd -f tools/dlq/pom.xml clean verify
java -jar tools/dlq/target/mecanica-dlq.jar inspect --local --queue os.reparo-cancelado.v1.dlq
```

A inspeção lê apenas a primeira mensagem e a devolve à fila. Verifique tipo, versão, identificadores e correlação, investigue os logs e corrija a causa. O corpo não é exibido nem armazenado na auditoria. O hash identifica exatamente o conteúdo inspecionado. Uma decisão conflitante ou contrato inválido exige investigação; não deve ser reenviado como falha transitória.

Após análise, substitua os valores abaixo pelos dados reais:

```powershell
java -jar tools/dlq/target/mecanica-dlq.jar replay --local --queue os.reparo-cancelado.v1.dlq --sha256 'HASH_DA_INSPECAO' --operator 'SEU_NOME' --reason 'Descreva a causa corrigida e a verificacao realizada' --classification transient --cause-fixed
java -jar tools/dlq/target/mecanica-dlq.jar history --local
```

O motivo deve ter pelo menos 12 caracteres. Se outra mensagem ocupar a posição inspecionada, a diferença de hash bloqueia o reenvio. Não há edição do envelope, reprocessamento em massa ou limpeza de filas. Consulte `--help` para as filas aceitas.

## Confirmação e auditoria

A ferramenta registra a intenção em SQLite antes de publicar, preserva corpo e identificador originais e envia diretamente à fila de destino, sem repetir a distribuição para outros consumidores. Usa mensagem persistente, publicação mandatory e confirmação do broker. Só então envia o reconhecimento da mensagem original na DLQ. Metadados de mortes anteriores são removidos; cabeçalhos da nova tentativa permitem correlacionar uma eventual rejeição.

- `PREPARED`: intenção registrada; interrupção neste ponto exige investigação.
- `UNKNOWN`: publicação sem confirmação segura; repetição automática bloqueada.
- `CONFIRMED`: broker confirmou a publicação; reconhecimento da origem ainda pode estar pendente.
- `ACK_SENT`: reconhecimento da origem enviado. Não comprova sucesso de negócio; consulte o serviço destinatário e a DLQ.

Se a origem reaparecer depois da confirmação, a mesma auditoria permite reconhecê-la sem publicar de novo. Se o consumidor rejeitar a mensagem reenviada, uma nova tentativa exige nova análise e `--retry-of ID_DA_TENTATIVA_ANTERIOR`. Estados ambíguos PREPARED/UNKNOWN não possuem comando de resolução automática: investigue broker, consumidor e auditoria antes de qualquer intervenção. Não apague a auditoria para contornar o bloqueio.

O arquivo padrão é `%LOCALAPPDATA%/MecanicaDevTools/dlq-audit.sqlite3`. Preserve-o entre tentativas e mantenha backup. `--audit` permite um caminho explícito, que deve ser compartilhado pelas operações concorrentes locais. A auditoria registra responsável, motivo, hash, IDs, origem, destino, estados e transições; `history` mostra as últimas 100 tentativas. Ela não é imutável, não autentica a identidade informada e não coordena máquinas com arquivos separados.

A operação continua sujeita a entrega pelo menos uma vez; consumidores devem permanecer idempotentes. A validação do envelope não substitui regras de negócio nem comprova que a causa foi corrigida. Credenciais, TLS, autorização e armazenamento central de auditoria para produção permanecem pendentes. O modo local conecta somente ao broker isolado em 127.0.0.1:5673.

Referências: [cliente Java do RabbitMQ](https://www.rabbitmq.com/client-libraries/java-api-guide) e [HTTP API RabbitMQ](https://www.rabbitmq.com/docs/http-api-reference). O reenvio operacional usa AMQP; a API HTTP é usada apenas pelo roteiro de diagnóstico local.

## Validação

```powershell
.\mvnw.cmd -f tools/dlq/pom.xml clean verify
.\scripts\validar-reprocessamento.ps1
```

Os 16 testes Java passaram, cobrindo inspeção, validação, confirmação, falhas na auditoria/publicação, concorrência e retomada após interrupção. A CI do Execution executa essa suíte.

Em `2026-11-04T12:34:36.2432621+00:00`, o roteiro criou uma OS e um reparo, cancelou o reparo e colocou uma cópia do evento original na DLQ para simular uma falha transitória corrigida. O broker e o consumidor da OS foram reais: a inspeção preservou a mensagem, o reenvio foi consumido e a OS permaneceu CANCELADA. Não demonstra uma indisponibilidade real nem pagamento.

- OS: `52b34826-1cca-4adc-8757-5b014f7fca0d`.
- Reparo: `1234a593-de65-4315-9257-ebe01bf6ec92`.
- Tentativa: `adb017e1-0b93-4eea-a9df-11fb4a2e283a`.
- Origem: `os.reparo-cancelado.v1.dlq`; destino: `os.reparo-cancelado.v1`.
- Relatório: `%LOCALAPPDATA%/MecanicaDevTools/ultima-validacao-dlq.json`.

O roteiro exige a DLQ selecionada vazia e não apaga mensagens preexistentes. A aprovação conflitante de outro cenário permanece em sua própria DLQ para investigação.

## Migração para Java — 04/11/2026

A ferramenta usa Java 21, cliente Java RabbitMQ, Jackson e SQLite JDBC, compilados pelo Maven em um JAR executável separado do backend. Não é necessário Python. A CI executa `./mvnw -B -f tools/dlq/pom.xml clean verify` e publica os relatórios JUnit. O `verify` da raiz do serviço não executa automaticamente este projeto auxiliar; use o comando explícito acima.

Os 16 testes cobrem também NACK do broker, retorno mandatory sem rota e reabertura da auditoria. O registro anterior `adb017e1-0b93-4eea-a9df-11fb4a2e283a` foi lido pela ferramenta Java com estado ACK_SENT: o arquivo SQLite e o histórico foram preservados.

A validação Java com broker/consumidor reais passou em `2026-11-04T17:21:34.0354009+00:00`, usando novamente uma falha transitória simulada. OS `025cb04b-fad9-4bf1-9a01-18bd81b9d1e5`, reparo `7678f063-be0f-491c-a047-49783a1a3278`, tentativa `85261a10-b636-4d9d-9b35-e18c7b8b468b`. OS preservada em CANCELADA, DLQ esvaziada e auditoria confirmada. Não houve pagamento real.