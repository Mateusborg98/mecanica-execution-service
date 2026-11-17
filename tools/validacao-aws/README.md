# Validacao integrada do laboratorio AWS

Java 21 executa os cenarios. O launcher PowerShell 7.3+ compila, abre port-forwards exclusivos em 19081/19082/19083, assina um JWT de demonstracao em memoria com a chave local DPAPI e fecha somente os processos que iniciou. Nao imprime JWT nem grava chave privada em arquivo.

Pre-requisitos: sessao AWS valida, contexto mecanica-fase4 configurado, tres APIs Ready, chave publica local ja cadastrada nos Secrets, kubectl 1.36 instalado em MecanicaDevTools. O issuer do ambiente de demonstracao e mecanica-auth. Isso NAO valida o login Lambda herdado da Fase 3.

Execute `./validar.ps1` nesta pasta. O Java exige a confirmacao explicita `--confirmar-laboratorio`, fornecida pelo launcher. Cria cadastros ficticios persistentes e dois atendimentos: um aprovado/concluido com cobranca pendente e entrega bloqueada (422), outro recusado com liberacao da reserva. Verifica tambem fila, valor, correlacao da cobranca e repeticao de recusa sem duplicar devolucao de estoque.

Nao chama SMTP, checkout, pagamento, webhook, simulador financeiro nem endpoints de reprocessamento. Nao interrompe RabbitMQ. Nao remove os registros depois: os IDs e o correlationId ficam em target/resultado-aws.json para consulta e evidencias. Saida de erro nao inclui corpos HTTP para evitar exposicao de dados.

O BDD distribuido do Compose continua separado. Esta validacao nao substitui pagamento real de teste, teste de falha do broker no ambiente entregue ou demonstracao de CD pelo GitHub.
