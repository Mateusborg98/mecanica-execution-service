package br.com.techchallenge.mecanica.execution.infrastructure.messaging;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import tools.jackson.databind.json.JsonMapper;


public class RabbitConclusaoPublisher implements java.util.function.Consumer<ReparoConcluido> {
    private final RabbitTemplate rabbit;
    private final JsonMapper json = new JsonMapper();
    public RabbitConclusaoPublisher(RabbitTemplate rabbit) { this.rabbit = rabbit; }
    public void accept(ReparoConcluido evento) {
        var eventId = evento.eventId();
        var props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setMessageId(eventId.toString());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbit.send(RabbitTopology.EXCHANGE, RabbitTopology.CONCLUSAO_ROUTE, new Message(json.writeValueAsBytes(evento), props), correlation);
        try {
            var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null)
                throw new IllegalStateException("Publicação não confirmada ou sem fila de destino.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publicação interrompida.", exception);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("Sem confirmação do broker.", exception);
        }
    }
}
