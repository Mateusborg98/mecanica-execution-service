package br.com.techchallenge.mecanica.execution.infrastructure.messaging;

import org.springframework.amqp.rabbit.core.RabbitTemplate;

public class RabbitInicioPublisher implements java.util.function.Consumer<ReparoIniciado> {
    private final PublicadorRabbitConfirmado publicador;

    public RabbitInicioPublisher(RabbitTemplate rabbit) {
        this.publicador = new PublicadorRabbitConfirmado(rabbit);
    }
    public void accept(ReparoIniciado evento) {
        var eventId = evento.eventId();
        publicador.publicar(eventId, evento, "reparo.iniciado.v1");
    }
}
