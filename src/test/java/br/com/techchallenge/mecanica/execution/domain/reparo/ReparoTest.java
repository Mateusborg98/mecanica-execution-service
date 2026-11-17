package br.com.techchallenge.mecanica.execution.domain.reparo;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReparoTest {
    private final Instant agora = Instant.parse("2026-10-14T12:00:00Z");
    private Reparo novo() { return new Reparo(UUID.randomUUID(), agora); }
    @Test void deveConcluirSomenteAposAprovacaoEInicio() {
        var ordemId = UUID.randomUUID(); var orcamentoId = UUID.randomUUID();
        var reparo = new Reparo(ordemId, agora);
        assertNotNull(reparo.getId()); assertEquals(ordemId, reparo.getOrdemId());
        assertEquals(agora, reparo.getCriadoEm()); assertNull(reparo.getIniciadoEm()); assertNull(reparo.getConcluidoEm());
        assertEquals(StatusReparo.AGUARDANDO_APROVACAO, reparo.getStatus());
        reparo.liberar(orcamentoId);
        assertEquals(orcamentoId, reparo.getOrcamentoId()); assertEquals(StatusReparo.NA_FILA, reparo.getStatus());
        reparo.iniciar(agora.plusSeconds(1));
        assertEquals(StatusReparo.EM_EXECUCAO, reparo.getStatus());
        reparo.concluir(agora.plusSeconds(2));
        assertEquals(StatusReparo.CONCLUIDO, reparo.getStatus());
        assertEquals(agora.plusSeconds(1), reparo.getIniciadoEm());
        assertEquals(agora.plusSeconds(2), reparo.getConcluidoEm());
    }
    @Test void naoDevePularAprovacaoOuInicio() {
        var r = novo(); assertThrows(IllegalStateException.class, () -> r.iniciar(agora));
        assertThrows(IllegalStateException.class, () -> r.concluir(agora));
        r.liberar(UUID.randomUUID()); assertThrows(IllegalStateException.class, () -> r.concluir(agora));
        assertThrows(IllegalStateException.class, () -> r.liberar(UUID.randomUUID()));
    }
    @Test void devePermitirCompensacaoAntesDeIniciar() {
        var pendente = novo(); pendente.cancelar(); assertEquals(StatusReparo.CANCELADO, pendente.getStatus());
        var fila = novo(); fila.liberar(UUID.randomUUID()); fila.cancelar();
        assertEquals(StatusReparo.CANCELADO, fila.getStatus());
        assertThrows(IllegalStateException.class, () -> fila.iniciar(agora));
    }
    @Test void naoDeveCancelarTrabalhoIniciadoOuConcluido() {
        var r = novo(); r.liberar(UUID.randomUUID()); r.iniciar(agora);
        assertThrows(IllegalStateException.class, r::cancelar);
        r.concluir(agora); assertThrows(IllegalStateException.class, r::cancelar);
        assertThrows(IllegalStateException.class, () -> r.concluir(agora));
    }
    @Test void deveValidarIdentificadoresEDatas() {
        assertThrows(IllegalArgumentException.class, () -> new Reparo(null, agora));
        assertThrows(IllegalArgumentException.class, () -> new Reparo(UUID.randomUUID(), null));
        var r = novo(); assertThrows(IllegalArgumentException.class, () -> r.liberar(null));
        r.liberar(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> r.iniciar(null));
        assertThrows(IllegalArgumentException.class, () -> r.iniciar(agora.minusSeconds(1)));
        assertEquals(StatusReparo.NA_FILA, r.getStatus());
        r.iniciar(agora.plusSeconds(10));
        assertThrows(IllegalArgumentException.class, () -> r.concluir(agora));
        assertThrows(IllegalArgumentException.class, () -> r.concluir(null));
        assertEquals(StatusReparo.EM_EXECUCAO, r.getStatus());
    }
}
