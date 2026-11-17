package br.com.techchallenge.mecanica.execution.infrastructure.messaging;

import java.util.UUID;

import org.springframework.amqp.rabbit.core.RabbitTemplate;

import br.com.techchallenge.mecanica.execution.application.PublicadorDiagnostico;
import br.com.techchallenge.mecanica.execution.domain.diagnostico.Diagnostico;
public class RabbitDiagnosticoPublisher implements PublicadorDiagnostico {
    private final PublicadorRabbitConfirmado publicador;

    public RabbitDiagnosticoPublisher(RabbitTemplate rabbit) {
        this.publicador = new PublicadorRabbitConfirmado(rabbit);
    }
    public void publicar(UUID eventId, Diagnostico d) {
        var evento = new DiagnosticoRegistrado(1, "DiagnosticoRegistrado", eventId, d.reparoId(), d.ordemId(), d.criadoEm(),
                d.itens().stream().map(i -> new DiagnosticoRegistrado.Item(i.tipo().name(), i.referenciaId(),
                        i.descricao(), i.quantidade(), i.valorUnitario())).toList());
        publicador.publicar(eventId, evento, RabbitTopology.ROUTE);
    }
}
