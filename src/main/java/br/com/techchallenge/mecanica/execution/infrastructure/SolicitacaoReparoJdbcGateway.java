package br.com.techchallenge.mecanica.execution.infrastructure;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import br.com.techchallenge.mecanica.execution.application.*;
public class SolicitacaoReparoJdbcGateway implements SolicitacaoReparoGateway {
    private final JdbcTemplate jdbc;
    private final Transacao tx;
    public SolicitacaoReparoJdbcGateway(JdbcTemplate jdbc, Transacao tx) { this.jdbc=jdbc; this.tx=tx; }
    public void receber(UUID ordemId, UUID reparoId, Instant ocorridoEm) {
        if(ordemId==null || reparoId==null || ocorridoEm==null) throw new IllegalArgumentException("Solicitação incompleta");
        tx.executar(() -> {
            jdbc.update("""
                INSERT INTO solicitacoes_reparo_recebidas(event_id,ordem_id,reparo_id,ocorrido_em)
                VALUES (?,?,?,?) ON CONFLICT DO NOTHING
                """,ordemId,ordemId,reparoId,Timestamp.from(ocorridoEm));
            var recebido=jdbc.queryForList("SELECT * FROM solicitacoes_reparo_recebidas WHERE event_id=? FOR UPDATE",ordemId);
            if(recebido.isEmpty()) throw new IllegalArgumentException("Reparo já associado a outra OS");
            var row=recebido.getFirst();
            UUID existente=jdbc.queryForObject("SELECT reparo_id FROM solicitacoes_reparo_recebidas WHERE event_id=?",UUID.class,ordemId);
            Instant data=jdbc.queryForObject("SELECT ocorrido_em FROM solicitacoes_reparo_recebidas WHERE event_id=?",(rs,n)->rs.getTimestamp(1).toInstant(),ordemId);
            if(!reparoId.equals(existente) || !ocorridoEm.equals(data)) throw new IllegalArgumentException("Solicitação conflitante");
            if(Boolean.TRUE.equals(row.get("processado"))) return null;
            var antigos=jdbc.queryForList("SELECT id FROM reparos WHERE ordem_id=?",UUID.class,ordemId);
            if(!antigos.isEmpty()) throw new IllegalArgumentException("A OS já tem reparo criado fora desta solicitação");
            jdbc.update("INSERT INTO reparos(id,ordem_id,criado_em,status,versao) VALUES (?,?,?,'AGUARDANDO_APROVACAO',0)",
                    reparoId,ordemId,Timestamp.from(ocorridoEm));
            jdbc.update("UPDATE solicitacoes_reparo_recebidas SET processado=TRUE WHERE event_id=?",ordemId);
            return null;
        });
    }
}
