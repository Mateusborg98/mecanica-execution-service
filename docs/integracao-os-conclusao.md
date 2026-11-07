# OS integrada e projeção da conclusão

Use PowerShell 7.3 ou superior no Windows, com Docker Desktop iniciado.
No repositório mecanica-execution-service:

```powershell
.\scripts\iniciar-integracao-os.ps1
.\scripts\validar-os-execution.ps1
```

O primeiro script habilita o profile `os` do Compose integrado, preserva volumes e
reconstrói as aplicações. A OS tem PostgreSQL próprio, separado do Execution.
As APIs ficam em 127.0.0.1:18081 (OS), 18082 (Billing) e 18083 (Execution).

A autenticação da OS continua habilitada. O script cria uma chave RSA exclusiva
de desenvolvimento e protege a chave privada com Windows DPAPI do usuário atual,
em `%LOCALAPPDATA%/MecanicaDevTools/os-integration-key.dpapi`. Somente a chave
pública é fornecida ao container. O teste emite um JWT local de 15 minutos,
usando um cliente da massa fictícia; não imprime ou grava o token.
Esse emissor local não substitui a integração com a autenticação da entrega em AWS.

## Fluxo validado pelo roteiro

1. Confere saúde dos três serviços e rejeição de consulta administrativa sem JWT.
2. Abre uma OS pela API e aguarda o reparo criado por mensageria no Execution.
3. Registra diagnóstico e reserva, aguarda orçamento no Billing e o aprova.
4. Inicia e conclui o reparo; aguarda OS FINALIZADA e cobrança PENDENTE.
5. Confirma rejeição da entrega sem pagamento.
6. Reenvia o mesmo evento de conclusão e verifica que não altera o resultado.

O teste cria uma nova massa a cada execução e preserva os dados anteriores.
O resumo, sem credenciais, fica em `ultima-validacao-os-execution.json` no mesmo
diretório local de ferramentas. Não realiza pagamentos nem simula confirmação financeira.

## Contrato da conclusão

O evento existente `ReparoConcluido` v1, rota `reparo.concluido.v1` do exchange
direto `mecanica.events`, chega a duas filas independentes: `billing.reparo-concluido.v1`
e `os.reparo-concluido.v1`. Cada consumidor recebe sua cópia.

Na OS, a migration V5 cria a inbox `conclusoes_reparo_recebidas`. O consumidor
bloqueia a linha da OS, valida a associação persistida com o reparo, o identificador
do orçamento e a data. A atualização para FINALIZADA, a data de fim em UTC e a
inbox são gravadas na mesma transação. Não marca pagamento nem entrega.

Uma duplicata idêntica é aceita sem regredir status, inclusive após a entrega.
Eventos conflitantes ou de reparos não vinculados são rejeitados para a DLQ
`os.reparo-concluido.v1.dlq`; erros transitórios seguem as retentativas configuradas.
O estado final é uma projeção da conclusão autoritativa do Execution, podendo chegar
antes de eventos intermediários que ainda não estão integrados nesta etapa.

## Pendências explícitas

- Projetar diagnóstico, aprovação e início na OS, com tratamento de ordem dos eventos.
- Projetar o valor aprovado: OS sem itens ainda mostra zero, enquanto a cobrança
  é calculada pelo diagnóstico no Billing. O consumidor de pagamento exige igualdade
  de valores, portanto essa pendência bloqueia a entrega paga nesse novo fluxo.
- Usar o vínculo persistido também na validação do pagamento recebido pela OS.
- Remover responsabilidades legadas duplicadas da OS e concluir as compensações da Saga.
- Validar pagamento real de teste no Mercado Pago e o fluxo até ENTREGUE.
- Integrar métricas/rastreamento das transições assíncronas ao Datadog.

Para encerrar este ambiente mantendo os dados:

```powershell
docker compose -f compose.integration.yaml --profile os down
```

## Evidência local — 27/10/2026

Builds aprovados: OS com 133 testes e Execution com 71 testes; cobertura mínima de 80% atendida em ambos.
Fluxo entre os três containers, RabbitMQ, dois PostgreSQL separados e MongoDB executado com sucesso:

- OS: ae1e9a17-6ede-42fd-8716-e8ab8cb0ac8d.
- Reparo criado automaticamente: 1eb71ca6-c029-4f8a-90c2-da6267b14860.
- Orçamento/cobrança: 7168fad8-1c33-3032-9c2a-18a32ee870ff.
- Resultado: OS FINALIZADA, cobrança PENDENTE de R$ 51,00.
- Consulta administrativa sem JWT rejeitada; abertura com JWT local aceita.
- Entrega sem pagamento rejeitada com HTTP 422.
- Evento de conclusão repetido confirmado sem alterar a data ou o status final.

Não houve pagamento no Mercado Pago. O valor aprovado ainda precisa ser projetado na OS para permitir concluir a validação do fluxo financeiro.

## Atualização em 03/11/2026

Status intermediários, valor aprovado e correlação do pagamento foram implementados e validados nos testes descritos em [progresso-os.md](progresso-os.md). Os itens históricos acima sobre essas três pendências foram superados; Mercado Pago, Saga completa e demais pendências permanecem.
