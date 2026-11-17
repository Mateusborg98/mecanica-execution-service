package br.com.techchallenge.mecanica.execution.infrastructure.messaging;

import org.springframework.amqp.rabbit.core.RabbitTemplate;

public class RabbitCancelamentoPublisher implements java.util.function.Consumer<ReparoCancelado> {
    private final PublicadorRabbitConfirmado publicador;

    public RabbitCancelamentoPublisher(RabbitTemplate rabbit) {
        this.publicador = new PublicadorRabbitConfirmado(rabbit);
    }
    public void accept(ReparoCancelado evento) {
        var eventId = evento.eventId();
        publicador.publicar(eventId, evento, "reparo.cancelado.v1");
    }
}
