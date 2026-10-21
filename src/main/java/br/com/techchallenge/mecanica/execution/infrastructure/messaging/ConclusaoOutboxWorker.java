package br.com.techchallenge.mecanica.execution.infrastructure.messaging;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import br.com.techchallenge.mecanica.execution.application.Transacao;
public class ConclusaoOutboxWorker {
    private final JdbcTemplate jdbc;
    private final Transacao tx;
    private final Consumer<ReparoConcluido> publisher;
    private final Clock clock;
    public ConclusaoOutboxWorker(JdbcTemplate jdbc, Transacao tx, Consumer<ReparoConcluido> publisher, Clock clock) {
        this.jdbc=jdbc; this.tx=tx; this.publisher=publisher; this.clock=clock;
    }
    @Scheduled(fixedDelayString="${integration.conclusao-outbox.delay-ms:2000}")
    public void agendar() { publicarProximo(); }
    public boolean publicarProximo() {
        return tx.executar(() -> {
            var agora=clock.instant();
            var eventos=jdbc.query("""
                    SELECT * FROM outbox_reparos_concluidos WHERE publicado_em IS NULL AND proxima_tentativa <= ?
                    ORDER BY ocorrido_em, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, (rs,n) -> new ReparoConcluido(1,"ReparoConcluido",rs.getObject("event_id",UUID.class),
                    rs.getObject("reparo_id",UUID.class),rs.getObject("ordem_id",UUID.class),
                    rs.getObject("orcamento_id",UUID.class),rs.getTimestamp("ocorrido_em").toInstant()), Timestamp.from(agora));
            if(eventos.isEmpty()) return false;
            var evento=eventos.getFirst();
            try { publisher.accept(evento); }
            catch(RuntimeException exception) {
                jdbc.update("""
                        UPDATE outbox_reparos_concluidos SET tentativas=tentativas+1, ultimo_erro=?, proxima_tentativa=? WHERE event_id=?
                        """,exception.getClass().getSimpleName(),Timestamp.from(agora.plusSeconds(30)),evento.eventId());
                return false;
            }
            jdbc.update("UPDATE outbox_reparos_concluidos SET publicado_em=?, tentativas=tentativas+1, ultimo_erro=NULL WHERE event_id=?",
                    Timestamp.from(clock.instant()),evento.eventId());
            return true;
        });
    }
}
