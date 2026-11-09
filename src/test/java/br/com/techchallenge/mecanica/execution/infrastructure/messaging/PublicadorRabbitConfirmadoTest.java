package br.com.techchallenge.mecanica.execution.infrastructure.messaging;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicadorRabbitConfirmadoTest {
    @Test
    void reenvioPreservaEventoMasUsaOutraCorrelacao() {
        var rabbit = mock(RabbitTemplate.class);
        var enviados = new ArrayList<CorrelationData>();
        var id = UUID.randomUUID();
        doAnswer(invocation -> {
            Message mensagem = invocation.getArgument(2);
            var propriedades = mensagem.getMessageProperties();
            assertEquals(id.toString(), propriedades.getMessageId());
            assertEquals("application/json", propriedades.getContentType());
            assertEquals("UTF-8", propriedades.getContentEncoding());
            assertEquals(MessageDeliveryMode.PERSISTENT, propriedades.getDeliveryMode());
            CorrelationData correlacao = invocation.getArgument(3);
            enviados.add(correlacao);
            correlacao.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(eq(RabbitTopology.EXCHANGE), eq("teste"), any(Message.class), any(CorrelationData.class));

        var publicador = new PublicadorRabbitConfirmado(rabbit);
        publicador.publicar(id, Map.of("eventId", id), "teste");
        publicador.publicar(id, Map.of("eventId", id), "teste");

        assertEquals(2, enviados.size());
        assertNotEquals(enviados.get(0).getId(), enviados.get(1).getId());
    }

    @Test
    void ausenciaDeConfirmacaoNaoPodeSerTratadaComoSucesso() {
        var publicador = new PublicadorRabbitConfirmado(mock(RabbitTemplate.class));
        var erro = assertThrows(IllegalStateException.class,
            () -> publicador.publicar(UUID.randomUUID(), Map.of(), "teste"));
        assertInstanceOf(TimeoutException.class, erro.getCause());
    }

    @Test
    void interrupcaoPreservaSinalParaEncerramentoDoWorker() {
        var publicador = new PublicadorRabbitConfirmado(mock(RabbitTemplate.class));
        try {
            Thread.currentThread().interrupt();
            var erro = assertThrows(IllegalStateException.class,
                () -> publicador.publicar(UUID.randomUUID(), Map.of(), "teste"));
            assertInstanceOf(InterruptedException.class, erro.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
