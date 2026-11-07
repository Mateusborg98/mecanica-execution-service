package br.com.techchallenge.mecanica.execution.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Transporte compartilhado pelos publishers deste serviço; não decide regras de negócio. */
final class PublicadorRabbitConfirmado {
    private final RabbitTemplate rabbit;
    private final JsonMapper json = new JsonMapper();

    PublicadorRabbitConfirmado(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    void publicar(UUID eventId, Object evento, String rota) {
        var propriedades = new MessageProperties();
        propriedades.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        propriedades.setContentEncoding(StandardCharsets.UTF_8.name());
        propriedades.setMessageId(eventId.toString());
        propriedades.setDeliveryMode(MessageDeliveryMode.PERSISTENT);

        // O evento mantém sua identidade; cada tentativa tem uma confirmação independente.
        var correlacao = new CorrelationData(UUID.randomUUID().toString());
        var mensagem = new Message(json.writeValueAsBytes(evento), propriedades);
        rabbit.send(RabbitTopology.EXCHANGE, rota, mensagem, correlacao);

        try {
            var confirmacao = correlacao.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirmacao.ack() || correlacao.getReturned() != null) {
                throw new IllegalStateException("Publicação não confirmada ou sem fila de destino.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publicação interrompida.", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Sem confirmação do broker.", exception);
        }
    }
}
