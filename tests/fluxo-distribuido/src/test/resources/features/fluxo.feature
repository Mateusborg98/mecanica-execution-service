# language: pt
Funcionalidade: Fluxo distribuído da oficina
  As APIs, bancos e RabbitMQ são reais. Somente o provedor externo de pagamento é simulado.

  Cenário: Entregar somente depois de confirmar o pagamento
    Dado uma ordem aberta pela API da OS e diagnosticada pela execução
    Quando o cliente aprova o orçamento e o reparo é concluído
    Então a entrega fica bloqueada enquanto a cobrança está pendente
    Quando o provedor confirma o pagamento por webhook assinado
    Então a cobrança fica paga e a OS pode ser entregue
    E o histórico registra o fluxo sem duplicar o pagamento

  Cenário: Recusar orçamento compensa a reserva
    Dado uma ordem aberta pela API da OS e diagnosticada pela execução
    Quando o cliente recusa o orçamento
    Então o reparo e a OS são cancelados e o estoque é restituído
  Cenário: Cancelamento antes da criação resiste à indisponibilidade do broker
    Dado uma ordem cuja solicitação de reparo ainda não foi publicada
    Quando o operador cancela a solicitação antes da criação do reparo
    Então a recuperação do broker não reabre a ordem cancelada
