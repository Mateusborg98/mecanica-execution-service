package br.com.techchallenge.mecanica.execution;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.dao.DataAccessResourceFailureException;
import br.com.techchallenge.mecanica.execution.application.SolicitacaoReparoGateway;
import br.com.techchallenge.mecanica.execution.infrastructure.messaging.*;
class SolicitacaoReparoListenerTest {
    final SolicitacaoReparoGateway gateway=mock(SolicitacaoReparoGateway.class);
    final SolicitacaoReparoListener listener=new SolicitacaoReparoListener(gateway);
    final String json="""
    {"schemaVersion":1,"eventType":"ReparoSolicitado","eventId":"22222222-2222-2222-2222-222222222222",
    "ordemId":"22222222-2222-2222-2222-222222222222","reparoId":"11111111-1111-1111-1111-111111111111",
    "ocorridoEm":"2026-10-27T15:00:00Z"}
    """;
    @Test void contratoValido() throws Exception { listener.receber(getClass().getResourceAsStream("/contracts/reparo-solicitado-v1.json").readAllBytes()); verify(gateway).receber(any(),any(),any()); }
    @Test void envelopeInvalidoRejeitado() {
        for(String body:new String[]{"{","null",json.replace("ReparoSolicitado","Outro"),json.replace("schemaVersion\":1","schemaVersion\":2")})
            assertThrows(AmqpRejectAndDontRequeueException.class,()->listener.receber(body.getBytes()));
        verifyNoInteractions(gateway);
    }
    @Test void falhaTransitoriaPermiteRetry() {
        doThrow(new DataAccessResourceFailureException("offline")).when(gateway).receber(any(),any(),any());
        assertThrows(DataAccessResourceFailureException.class,()->listener.receber(json.getBytes()));
    }
}
