package br.com.techchallenge.mecanica.execution.infrastructure;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import br.com.techchallenge.mecanica.execution.application.*;
import br.com.techchallenge.mecanica.execution.domain.diagnostico.*;
public class DiagnosticoJdbcGateway implements DiagnosticoGateway {
    private final JdbcTemplate jdbc;
    private final Transacao tx;
    private final Clock clock;
    public DiagnosticoJdbcGateway(JdbcTemplate jdbc, Transacao tx, Clock clock) {
        this.jdbc = jdbc; this.tx = tx; this.clock = clock;
    }
    public Servico criarServico(Servico s) {
        try {
            jdbc.update("INSERT INTO servicos(id, codigo, descricao, preco) VALUES (?, ?, ?, ?)", s.id(), s.codigo(), s.descricao(), s.preco());
            return buscarServico(s.id());
        } catch (DuplicateKeyException e) { throw new IllegalStateException("Código de serviço já cadastrado."); }
    }
    public Servico buscarServico(UUID id) {
        return jdbc.query("SELECT * FROM servicos WHERE id = ?", (rs, n) -> new Servico(
                rs.getObject("id", UUID.class), rs.getString("codigo"), rs.getString("descricao"), rs.getBigDecimal("preco")), id)
                .stream().findFirst().orElseThrow(() -> new RecursoNaoEncontradoException("Serviço não encontrado."));
    }
    public Diagnostico registrar(UUID reparoId, String observacoes, List<Diagnostico.Selecao> selecoes) {
        return tx.executar(() -> {
            var estados = jdbc.queryForList("SELECT status FROM reparos WHERE id = ? FOR UPDATE", String.class, reparoId);
            if (estados.isEmpty()) throw new ReparoNaoEncontradoException();
            var anterior = consultar(reparoId);
            if (anterior.isPresent()) {
                var d = anterior.get();
                if (!d.observacoes().equals(observacoes) || !d.itens().stream().map(Diagnostico.Item::selecao).toList().equals(selecoes))
                    throw new IllegalStateException("Diagnóstico já registrado com outro conteúdo.");
                return d;
            }
            if (!estados.getFirst().equals("AGUARDANDO_APROVACAO"))
                throw new IllegalStateException("Registre o diagnóstico antes da aprovação do orçamento.");
            if (!jdbc.queryForList("SELECT reparo_id FROM reservas_estoque WHERE reparo_id = ?", reparoId).isEmpty()) {
                var reservados = jdbc.query("SELECT peca_id, quantidade FROM itens_reserva WHERE reparo_id = ?",
                        (rs, n) -> new Diagnostico.Selecao(Diagnostico.Tipo.PECA, rs.getObject("peca_id", UUID.class), rs.getInt("quantidade")), reparoId);
                reservados.sort(Comparator.comparing(i -> i.referenciaId().toString()));
                var pecas = selecoes.stream().filter(i -> i.tipo() == Diagnostico.Tipo.PECA).toList();
                if (!pecas.equals(reservados)) throw new IllegalStateException("Diagnóstico incompatível com a reserva existente.");
            }
            jdbc.update("INSERT INTO diagnosticos(reparo_id, observacoes, criado_em) VALUES (?, ?, ?)",
                    reparoId, observacoes, Timestamp.from(Instant.now(clock).truncatedTo(ChronoUnit.MICROS)));
            int posicao = 0;
            for (var item : selecoes) {
                // Table name comes exclusively from the closed enum, never from raw request text.
                var tabela = item.tipo() == Diagnostico.Tipo.PECA ? "pecas" : "servicos";
                var valores = jdbc.query("SELECT descricao, preco FROM " + tabela + " WHERE id = ?",
                        (rs, n) -> new Diagnostico.Item(item.tipo(), item.referenciaId(), rs.getString("descricao"),
                                item.quantidade(), rs.getBigDecimal("preco")), item.referenciaId());
                if (valores.isEmpty()) throw new RecursoNaoEncontradoException("Item do diagnóstico não encontrado no catálogo.");
                var valor = valores.getFirst();
                jdbc.update("""
                        INSERT INTO itens_diagnostico(reparo_id, posicao, tipo, referencia_id, descricao, quantidade, valor_unitario)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, reparoId, posicao++, valor.tipo().name(), valor.referenciaId(), valor.descricao(), valor.quantidade(), valor.valorUnitario());
            }
            jdbc.update("""
                    INSERT INTO outbox_diagnosticos(event_id, reparo_id, criado_em, proxima_tentativa)
                    SELECT reparo_id, reparo_id, criado_em, criado_em FROM diagnosticos WHERE reparo_id = ?
                    """, reparoId);
            return buscar(reparoId);
        });
    }
    private Optional<Diagnostico> consultar(UUID reparoId) {
        var cabecalhos = jdbc.query("""
                SELECT d.*, r.ordem_id FROM diagnosticos d JOIN reparos r ON r.id = d.reparo_id WHERE d.reparo_id = ?
                """, (rs, n) -> new Diagnostico(reparoId, rs.getObject("ordem_id", UUID.class), rs.getString("observacoes"),
                        rs.getTimestamp("criado_em").toInstant(), List.of()), reparoId);
        if (cabecalhos.isEmpty()) return Optional.empty();
        var d = cabecalhos.getFirst();
        var itens = jdbc.query("SELECT * FROM itens_diagnostico WHERE reparo_id = ? ORDER BY posicao",
                (rs, n) -> new Diagnostico.Item(Diagnostico.Tipo.valueOf(rs.getString("tipo")), rs.getObject("referencia_id", UUID.class),
                        rs.getString("descricao"), rs.getInt("quantidade"), rs.getBigDecimal("valor_unitario")), reparoId);
        return Optional.of(new Diagnostico(reparoId, d.ordemId(), d.observacoes(), d.criadoEm(), itens));
    }
    public Diagnostico buscar(UUID reparoId) {
        return consultar(reparoId).orElseThrow(() -> new RecursoNaoEncontradoException("Diagnóstico não encontrado."));
    }
}
