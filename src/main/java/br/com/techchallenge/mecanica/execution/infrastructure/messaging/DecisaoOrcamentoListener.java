package br.com.techchallenge.mecanica.execution.infrastructure.messaging;
import java.util.Set;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.JacksonException;
import br.com.techchallenge.mecanica.execution.application.DecisaoOrcamentoGateway;
public class DecisaoOrcamentoListener {
    private final DecisaoOrcamentoGateway gateway;
    private final JsonMapper json = new JsonMapper();
    public DecisaoOrcamentoListener(DecisaoOrcamentoGateway gateway) { this.gateway = gateway; }
    @RabbitListener(queues=RabbitTopology.DECISAO_QUEUE)
    public void receber(byte[] body) {
        try {
            var e = json.readValue(body, OrcamentoDecidido.class);
            if (e == null || e.schemaVersion() != 1 || !"OrcamentoDecidido".equals(e.eventType())
                    || e.eventId() == null || e.orcamentoId() == null || e.reparoId() == null || e.ordemId() == null
                    || e.ocorridoEm() == null || e.decisao() == null || !Set.of("APROVADO", "RECUSADO").contains(e.decisao()))
                throw new IllegalArgumentException("Envelope de decisão inválido.");
            gateway.aplicar(e.eventId(), e.orcamentoId(), e.reparoId(), e.ordemId(), "APROVADO".equals(e.decisao()));
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new AmqpRejectAndDontRequeueException("Decisão inválida ou incompatível.", exception);
        }
    }
}
