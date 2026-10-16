package br.com.techchallenge.mecanica.execution;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.dao.DataAccessResourceFailureException;
import br.com.techchallenge.mecanica.execution.application.DecisaoOrcamentoGateway;
import br.com.techchallenge.mecanica.execution.infrastructure.messaging.*;
class DecisaoOrcamentoListenerTest {
    private byte[] fixture() throws Exception { return getClass().getResourceAsStream("/contracts/orcamento-decidido-v1.json").readAllBytes(); }
    @Test void deveAceitarContratoDeAprovacaoERecusa() throws Exception {
        var g=mock(DecisaoOrcamentoGateway.class); var listener=new DecisaoOrcamentoListener(g);
        listener.receber(fixture()); listener.receber(new String(fixture(),StandardCharsets.UTF_8).replace("APROVADO","RECUSADO").getBytes(StandardCharsets.UTF_8));
        verify(g).aplicar(any(),any(),any(),any(),eq(true)); verify(g).aplicar(any(),any(),any(),any(),eq(false));
    }
    @Test void invalidoDeveIrParaDlq() throws Exception {
        var g=mock(DecisaoOrcamentoGateway.class); var listener=new DecisaoOrcamentoListener(g);
        var original=new String(fixture(),StandardCharsets.UTF_8);
        for(var invalid:List.of("{","null",original.replace("APROVADO","PENDENTE"),original.replace("OrcamentoDecidido","Outro"),original.replace("\"schemaVersion\": 1","\"schemaVersion\": 99")))
            assertThrows(AmqpRejectAndDontRequeueException.class,()->listener.receber(invalid.getBytes(StandardCharsets.UTF_8)));
        verifyNoInteractions(g);
    }
    @Test void conflitoVaiParaDlqMasFalhaDeBancoPermiteRetry() throws Exception {
        var g=mock(DecisaoOrcamentoGateway.class); var listener=new DecisaoOrcamentoListener(g);
        doThrow(new IllegalArgumentException("Conflito")).doThrow(new DataAccessResourceFailureException("Offline"))
                .when(g).aplicar(any(),any(),any(),any(),anyBoolean());
        assertThrows(AmqpRejectAndDontRequeueException.class,()->listener.receber(fixture()));
        assertThrows(DataAccessResourceFailureException.class,()->listener.receber(fixture()));
    }
}
