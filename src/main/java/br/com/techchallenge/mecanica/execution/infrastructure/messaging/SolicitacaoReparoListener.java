package br.com.techchallenge.mecanica.execution.infrastructure.messaging;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.JacksonException;
import br.com.techchallenge.mecanica.execution.application.SolicitacaoReparoGateway;
public class SolicitacaoReparoListener {
    private final SolicitacaoReparoGateway gateway;
    private final JsonMapper json=new JsonMapper();
    public SolicitacaoReparoListener(SolicitacaoReparoGateway gateway) { this.gateway=gateway; }
    @RabbitListener(queues="execution.reparo-solicitado.v1")
    public void receber(byte[] body) {
        try {
            var e=json.readValue(body,ReparoSolicitado.class);
            if(e==null || e.schemaVersion()!=1 || !"ReparoSolicitado".equals(e.eventType())
                || e.eventId()==null || !e.eventId().equals(e.ordemId()) || e.reparoId()==null || e.ocorridoEm()==null
                || !e.ocorridoEm().equals(e.ocorridoEm().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)))
                throw new IllegalArgumentException("Envelope inválido");
            gateway.receber(e.ordemId(),e.reparoId(),e.ocorridoEm());
        } catch(JacksonException | IllegalArgumentException e) {
            throw new AmqpRejectAndDontRequeueException("Solicitação de reparo inválida ou conflitante",e);
        }
    }
}
