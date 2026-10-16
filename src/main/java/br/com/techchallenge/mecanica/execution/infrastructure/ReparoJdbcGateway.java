package br.com.techchallenge.mecanica.execution.infrastructure;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import br.com.techchallenge.mecanica.execution.application.*;
import br.com.techchallenge.mecanica.execution.domain.reparo.*;
public class ReparoJdbcGateway implements ReparoGateway {
    private final JdbcTemplate jdbc;
    public ReparoJdbcGateway(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void inserir(Reparo r) {
        try {
            jdbc.update("INSERT INTO reparos(id, ordem_id, criado_em, status, versao) VALUES (?, ?, ?, ?, 0)",
                    r.getId(), r.getOrdemId(), Timestamp.from(r.getCriadoEm()), r.getStatus().name());
        } catch (DuplicateKeyException exception) { throw new ConflitoReparoException(); }
    }
    public Optional<Registro> buscar(UUID id) {
        return jdbc.query("SELECT * FROM reparos WHERE id = ?", (rs, row) -> new Registro(
                Reparo.reconstituir(rs.getObject("id", UUID.class), rs.getObject("ordem_id", UUID.class),
                    rs.getTimestamp("criado_em").toInstant(), rs.getObject("orcamento_id", UUID.class),
                    StatusReparo.valueOf(rs.getString("status")), instant(rs.getTimestamp("iniciado_em")),
                    instant(rs.getTimestamp("concluido_em"))), rs.getLong("versao")), id).stream().findFirst();
    }
    public void atualizar(Registro registro) {
        var r = registro.reparo();
        int linhas = jdbc.update("""
                UPDATE reparos SET orcamento_id = ?, status = ?, iniciado_em = ?, concluido_em = ?, versao = versao + 1
                WHERE id = ? AND versao = ?
                """, r.getOrcamentoId(), r.getStatus().name(), timestamp(r.getIniciadoEm()),
                timestamp(r.getConcluidoEm()), r.getId(), registro.versao());
        if (linhas != 1) throw new ConflitoReparoException();
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
}
