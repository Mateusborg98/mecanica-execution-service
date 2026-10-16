package br.com.techchallenge.mecanica.execution.infrastructure;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import br.com.techchallenge.mecanica.execution.application.*;
import br.com.techchallenge.mecanica.execution.domain.reparo.StatusReparo;
public class DecisaoOrcamentoJdbcGateway implements DecisaoOrcamentoGateway {
    private final JdbcTemplate jdbc;
    private final Transacao tx;
    private final ReparoService reparos;
    public DecisaoOrcamentoJdbcGateway(JdbcTemplate jdbc, Transacao tx, ReparoService reparos) {
        this.jdbc = jdbc; this.tx = tx; this.reparos = reparos;
    }
    public void aplicar(UUID eventId, UUID orcamentoId, UUID reparoId, UUID ordemId, boolean aprovado) {
        var esperado = UUID.nameUUIDFromBytes(("mecanica:diagnostico:v1:" + reparoId).getBytes(StandardCharsets.UTF_8));
        if (!esperado.equals(orcamentoId) || !orcamentoId.equals(eventId))
            throw new IllegalArgumentException("Orçamento não corresponde ao diagnóstico.");
        tx.executar(() -> {
            if (jdbc.queryForList("SELECT id FROM reparos WHERE id = ? FOR UPDATE", reparoId).isEmpty())
                throw new IllegalArgumentException("Reparo não encontrado para a decisão.");
            var reparo = reparos.buscar(reparoId);
            if (!reparo.getOrdemId().equals(ordemId) || jdbc.queryForList(
                    "SELECT reparo_id FROM diagnosticos WHERE reparo_id = ?", reparoId).isEmpty())
                throw new IllegalArgumentException("Ordem ou diagnóstico incompatível.");
            var anterior = jdbc.queryForList("SELECT aprovado FROM decisoes_orcamento_recebidas WHERE event_id = ?", Boolean.class, eventId);
            if (!anterior.isEmpty()) {
                if (anterior.getFirst() != aprovado) throw new IllegalArgumentException("Decisão repetida com conteúdo diferente.");
                return null;
            }
            if (reparo.getStatus() != StatusReparo.AGUARDANDO_APROVACAO
                    && (aprovado || reparo.getStatus() != StatusReparo.CANCELADO))
                throw new IllegalArgumentException("Decisão incompatível com o estado do reparo.");
            if (aprovado) reparos.liberar(reparoId, orcamentoId);
            else reparos.recusarOrcamento(reparoId, orcamentoId);
            jdbc.update("INSERT INTO decisoes_orcamento_recebidas(event_id, orcamento_id, reparo_id, ordem_id, aprovado) VALUES (?, ?, ?, ?, ?)",
                    eventId, orcamentoId, reparoId, ordemId, aprovado);
            return null;
        });
    }
}
