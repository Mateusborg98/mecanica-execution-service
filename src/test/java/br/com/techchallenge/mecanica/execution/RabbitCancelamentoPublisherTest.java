package br.com.techchallenge.mecanica.execution;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import tools.jackson.databind.json.JsonMapper;

import br.com.techchallenge.mecanica.execution.infrastructure.messaging.*;
class RabbitCancelamentoPublisherTest {
    private final UUID id=UUID.fromString("11111111-1111-1111-1111-111111111111");
    private ReparoCancelado evento() {
        return new ReparoCancelado(1,"ReparoCancelado",id,id,UUID.fromString("22222222-2222-2222-2222-222222222222"),
                Instant.parse("2026-10-20T15:00:00Z"));
    }
    @Test void devePublicarContratoVersionadoPersistenteComConfirmacao() throws Exception {
        var rabbit = mock(RabbitTemplate.class); var json = new JsonMapper();
        var fixture = getClass().getResourceAsStream("/contracts/reparo-cancelado-v1.json").readAllBytes();
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            assertEquals(json.readTree(fixture), json.readTree(message.getBody()));
            assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getDeliveryMode());
            assertEquals(id.toString(), message.getMessageProperties().getMessageId());
            CorrelationData cd = invocation.getArgument(3); cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(eq(RabbitTopology.EXCHANGE), eq("reparo.cancelado.v1"), any(Message.class), any(CorrelationData.class));
        new RabbitCancelamentoPublisher(rabbit).accept(evento());
        verify(rabbit).send(eq(RabbitTopology.EXCHANGE), eq("reparo.cancelado.v1"), any(Message.class), any(CorrelationData.class));
    }
    @Test void naoDeveAceitarNackOuMensagemSemRota() {
        for (boolean returned : List.of(false, true)) {
            var rabbit = mock(RabbitTemplate.class);
            doAnswer(invocation -> {
                CorrelationData cd = invocation.getArgument(3);
                if (returned) cd.setReturned(new ReturnedMessage(invocation.getArgument(2), 312, "NO_ROUTE", RabbitTopology.EXCHANGE, "reparo.cancelado.v1"));
                cd.getFuture().complete(new CorrelationData.Confirm(returned, "nack")); return null;
            }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
            assertThrows(IllegalStateException.class, () -> new RabbitCancelamentoPublisher(rabbit).accept(evento()));
        }
    }
    @Test void devePropagarFalhaDeConfirmacao() {
        var rabbit = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3); cd.getFuture().completeExceptionally(new IllegalStateException("falha")); return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThrows(IllegalStateException.class, () -> new RabbitCancelamentoPublisher(rabbit).accept(evento()));
    }
}
