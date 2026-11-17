package br.com.techchallenge.mecanica.execution.infrastructure.messaging;

import org.springframework.amqp.rabbit.core.RabbitTemplate;

public class RabbitConclusaoPublisher implements java.util.function.Consumer<ReparoConcluido> {
    private final PublicadorRabbitConfirmado publicador;

    public RabbitConclusaoPublisher(RabbitTemplate rabbit) {
        this.publicador = new PublicadorRabbitConfirmado(rabbit);
    }
    public void accept(ReparoConcluido evento) {
        var eventId = evento.eventId();
        publicador.publicar(eventId, evento, RabbitTopology.CONCLUSAO_ROUTE);
    }
}
