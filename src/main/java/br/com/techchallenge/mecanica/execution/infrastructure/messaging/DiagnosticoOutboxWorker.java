package br.com.techchallenge.mecanica.execution.infrastructure.messaging;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import br.com.techchallenge.mecanica.execution.application.*;
public class DiagnosticoOutboxWorker {
    private final JdbcTemplate jdbc;
    private final Transacao tx;
    private final DiagnosticoGateway diagnosticos;
    private final PublicadorDiagnostico publicador;
    private final Clock clock;
    public DiagnosticoOutboxWorker(JdbcTemplate jdbc, Transacao tx, DiagnosticoGateway diagnosticos,
            PublicadorDiagnostico publicador, Clock clock) {
        this.jdbc=jdbc; this.tx=tx; this.diagnosticos=diagnosticos; this.publicador=publicador; this.clock=clock;
    }
    @Scheduled(fixedDelayString="${integration.outbox.delay-ms:2000}")
    public void agendar() { publicarProximo(); }
    public boolean publicarProximo() {
        return tx.executar(() -> {
            var agora = Instant.now(clock);
            var ids = jdbc.queryForList("""
                    SELECT reparo_id FROM outbox_diagnosticos
                    WHERE publicado_em IS NULL AND proxima_tentativa <= ?
                    ORDER BY criado_em, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, UUID.class, Timestamp.from(agora));
            if (ids.isEmpty()) return false;
            var id = ids.getFirst();
            try { publicador.publicar(id, diagnosticos.buscar(id)); }
            catch (RuntimeException exception) {
                jdbc.update("""
                        UPDATE outbox_diagnosticos SET tentativas = tentativas + 1, ultimo_erro = ?, proxima_tentativa = ?
                        WHERE event_id = ?
                        """, exception.getClass().getSimpleName(), Timestamp.from(agora.plusSeconds(30)), id);
                return false;
            }
            jdbc.update("UPDATE outbox_diagnosticos SET publicado_em = ?, tentativas = tentativas + 1, ultimo_erro = NULL WHERE event_id = ?",
                    Timestamp.from(Instant.now(clock)), id);
            return true;
        });
    }
}
