# language: pt
Funcionalidade: Execução e compensação de um reparo
  A oficina deve executar somente reparos aprovados e preservar o estoque.

  Contexto:
    Dado um reparo aguardando aprovação com duas peças reservadas de um estoque de cinco

  Cenário: Concluir um reparo aprovado
    Quando o orçamento do reparo é aprovado
    E o operador inicia o reparo
    E o operador conclui o reparo
    Então o reparo permanece concluído ao ser consultado
    E o estoque disponível é 3 e a reserva está "CONSUMIDA"
    E existe um único evento de conclusão pendente na outbox
    E uma tentativa de cancelar o reparo é rejeitada

  Cenário: Impedir execução sem autorização do cliente
    Quando o operador tenta iniciar o reparo sem aprovação
    Então a operação é rejeitada e o reparo continua aguardando aprovação
    E o estoque disponível é 3 e a reserva está "RESERVADA"
    E não existe evento de conclusão na outbox

  Cenário: Compensar cancelamento antes do início sem duplicar estoque
    Quando o orçamento do reparo é aprovado
    E o operador cancela o reparo duas vezes
    Então o reparo permanece cancelado ao ser consultado
    E o estoque disponível é 5 e a reserva está "LIBERADA"
    E existe um único evento de cancelamento pendente na outbox
    E uma tentativa de iniciar o reparo é rejeitada
