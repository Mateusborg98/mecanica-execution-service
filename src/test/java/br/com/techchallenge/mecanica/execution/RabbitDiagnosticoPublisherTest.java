package br.com.techchallenge.mecanica.execution;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import tools.jackson.databind.json.JsonMapper;
import br.com.techchallenge.mecanica.execution.domain.diagnostico.Diagnostico;
import br.com.techchallenge.mecanica.execution.infrastructure.messaging.*;
class RabbitDiagnosticoPublisherTest {
    private final UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private Diagnostico diagnostico() {
        return new Diagnostico(id, UUID.fromString("22222222-2222-2222-2222-222222222222"), "Inspecao",
                Instant.parse("2026-10-16T12:00:00Z"), List.of(new Diagnostico.Item(Diagnostico.Tipo.SERVICO,
                UUID.fromString("33333333-3333-3333-3333-333333333333"), "Inspecao", 1, new BigDecimal("100.00"))));
    }
    @Test void devePublicarContratoVersionadoPersistenteComConfirmacao() throws Exception {
        var rabbit = mock(RabbitTemplate.class); var json = new JsonMapper();
        var fixture = getClass().getResourceAsStream("/contracts/diagnostico-registrado-v1.json").readAllBytes();
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            assertEquals(json.readTree(fixture), json.readTree(message.getBody()));
            assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getDeliveryMode());
            assertEquals(id.toString(), message.getMessageProperties().getMessageId());
            CorrelationData cd = invocation.getArgument(3); cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(eq(RabbitTopology.EXCHANGE), eq(RabbitTopology.ROUTE), any(Message.class), any(CorrelationData.class));
        new RabbitDiagnosticoPublisher(rabbit).publicar(id, diagnostico());
        verify(rabbit).send(eq(RabbitTopology.EXCHANGE), eq(RabbitTopology.ROUTE), any(Message.class), any(CorrelationData.class));
    }
    @Test void naoDeveAceitarNackOuMensagemSemRota() {
        for (boolean returned : List.of(false, true)) {
            var rabbit = mock(RabbitTemplate.class);
            doAnswer(invocation -> {
                CorrelationData cd = invocation.getArgument(3);
                if (returned) cd.setReturned(new ReturnedMessage(invocation.getArgument(2), 312, "NO_ROUTE", RabbitTopology.EXCHANGE, RabbitTopology.ROUTE));
                cd.getFuture().complete(new CorrelationData.Confirm(returned, "nack")); return null;
            }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
            assertThrows(IllegalStateException.class, () -> new RabbitDiagnosticoPublisher(rabbit).publicar(id, diagnostico()));
        }
    }
    @Test void devePropagarFalhaDeConfirmacao() {
        var rabbit = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3); cd.getFuture().completeExceptionally(new IllegalStateException("falha")); return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThrows(IllegalStateException.class, () -> new RabbitDiagnosticoPublisher(rabbit).publicar(id, diagnostico()));
    }
}
