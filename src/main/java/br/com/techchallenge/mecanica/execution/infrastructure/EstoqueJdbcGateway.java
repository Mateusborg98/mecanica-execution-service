package br.com.techchallenge.mecanica.execution.infrastructure;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import br.com.techchallenge.mecanica.execution.application.*;
import br.com.techchallenge.mecanica.execution.domain.estoque.*;

public class EstoqueJdbcGateway implements EstoqueGateway {
    private final JdbcTemplate jdbc;
    private final Transacao tx;
    public EstoqueJdbcGateway(JdbcTemplate jdbc, Transacao tx) { this.jdbc = jdbc; this.tx = tx; }

    public Peca criar(Peca p) {
        try {
            jdbc.update("INSERT INTO pecas(id, sku, descricao, preco, disponivel) VALUES (?, ?, ?, ?, 0)",
                    p.id(), p.sku(), p.descricao(), p.preco());
            return buscar(p.id());
        } catch (DuplicateKeyException e) { throw new IllegalStateException("SKU já cadastrado."); }
    }
    public Peca buscar(UUID id) {
        return jdbc.query("SELECT * FROM pecas WHERE id = ?", (rs, n) -> new Peca(
                rs.getObject("id", UUID.class), rs.getString("sku"), rs.getString("descricao"),
                rs.getBigDecimal("preco"), rs.getInt("disponivel")), id).stream().findFirst()
                .orElseThrow(() -> new RecursoNaoEncontradoException("Peça não encontrada."));
    }
    private void bloquearPeca(UUID id) {
        if (jdbc.queryForList("SELECT id FROM pecas WHERE id = ? FOR UPDATE", id).isEmpty())
            throw new RecursoNaoEncontradoException("Peça não encontrada.");
    }
    public Peca entrada(UUID movimentoId, UUID pecaId, int quantidade) {
        return tx.executar(() -> {
            bloquearPeca(pecaId);
            var existente = jdbc.query("SELECT peca_id, quantidade FROM entradas_estoque WHERE id = ?",
                    (rs, n) -> new ItemReserva(rs.getObject("peca_id", UUID.class), rs.getInt("quantidade")), movimentoId);
            if (!existente.isEmpty()) {
                if (!existente.getFirst().equals(new ItemReserva(pecaId, quantidade)))
                    throw new IllegalStateException("Identificador do movimento reutilizado com dados diferentes.");
                return buscar(pecaId);
            }
            try {
                jdbc.update("INSERT INTO entradas_estoque(id, peca_id, quantidade) VALUES (?, ?, ?)", movimentoId, pecaId, quantidade);
            } catch (DuplicateKeyException e) { throw new IllegalStateException("Movimento já utilizado."); }
            if (jdbc.update("UPDATE pecas SET disponivel = disponivel + ? WHERE id = ? AND disponivel <= ?",
                    quantidade, pecaId, Integer.MAX_VALUE - quantidade) != 1)
                throw new IllegalStateException("Limite de estoque excedido.");
            return buscar(pecaId);
        });
    }
    public Reserva reservar(UUID reparoId, List<ItemReserva> itens) {
        return tx.executar(() -> {
            var estados = jdbc.queryForList("SELECT status FROM reparos WHERE id = ? FOR UPDATE", String.class, reparoId);
            if (estados.isEmpty()) throw new ReparoNaoEncontradoException();
            var anterior = reserva(reparoId);
            if (anterior.isPresent()) {
                if (!anterior.get().itens().equals(itens)) throw new IllegalStateException("Reparo já possui outra reserva.");
                return anterior.get();
            }
            if (!Set.of("AGUARDANDO_APROVACAO", "NA_FILA").contains(estados.getFirst()))
                throw new IllegalStateException("Só é possível reservar antes de iniciar o reparo.");
            if (!jdbc.queryForList("SELECT reparo_id FROM diagnosticos WHERE reparo_id = ?", reparoId).isEmpty()) {
                var necessarios = jdbc.query("SELECT referencia_id, quantidade FROM itens_diagnostico WHERE reparo_id = ? AND tipo = 'PECA'",
                        (rs, n) -> new ItemReserva(rs.getObject("referencia_id", UUID.class), rs.getInt("quantidade")), reparoId);
                necessarios.sort(Comparator.comparing(i -> i.pecaId().toString()));
                if (!necessarios.equals(itens)) throw new IllegalStateException("Reserva incompatível com o diagnóstico.");
            }
            jdbc.update("INSERT INTO reservas_estoque(reparo_id, status) VALUES (?, 'RESERVADA')", reparoId);
            for (var item : itens) {
                bloquearPeca(item.pecaId());
                if (jdbc.update("UPDATE pecas SET disponivel = disponivel - ? WHERE id = ? AND disponivel >= ?",
                        item.quantidade(), item.pecaId(), item.quantidade()) != 1)
                    throw new IllegalStateException("Estoque insuficiente.");
                jdbc.update("INSERT INTO itens_reserva(reparo_id, peca_id, quantidade) VALUES (?, ?, ?)",
                        reparoId, item.pecaId(), item.quantidade());
            }
            return buscarReserva(reparoId);
        });
    }
    private Optional<Reserva> reserva(UUID reparoId) {
        var estados = jdbc.queryForList("SELECT status FROM reservas_estoque WHERE reparo_id = ?", String.class, reparoId);
        if (estados.isEmpty()) return Optional.empty();
        var itens = jdbc.query("SELECT peca_id, quantidade FROM itens_reserva WHERE reparo_id = ? ORDER BY peca_id",
                (rs, n) -> new ItemReserva(rs.getObject("peca_id", UUID.class), rs.getInt("quantidade")), reparoId);
        // UUID ordering differs across databases; the contract uses canonical string ordering.
        itens.sort(Comparator.comparing(i -> i.pecaId().toString()));
        return Optional.of(new Reserva(reparoId, Reserva.Status.valueOf(estados.getFirst()), itens));
    }
    public Reserva buscarReserva(UUID reparoId) {
        return reserva(reparoId).orElseThrow(() -> new RecursoNaoEncontradoException("Reserva não encontrada."));
    }
    // Called inside the same transaction that updates the repair (repair row is locked first).
    public void consumir(UUID reparoId) {
        if (!jdbc.queryForList("SELECT reparo_id FROM diagnosticos WHERE reparo_id = ?", reparoId).isEmpty()) {
            var necessarios = jdbc.query("SELECT referencia_id, quantidade FROM itens_diagnostico WHERE reparo_id = ? AND tipo = 'PECA'",
                    (rs, n) -> new ItemReserva(rs.getObject("referencia_id", UUID.class), rs.getInt("quantidade")), reparoId);
            necessarios.sort(Comparator.comparing(i -> i.pecaId().toString()));
            var atual = reserva(reparoId);
            var reservados = atual.map(Reserva::itens).orElse(List.of());
            if (!necessarios.equals(reservados) || (atual.isPresent() && atual.get().status() != Reserva.Status.RESERVADA))
                throw new IllegalStateException("Reserve exatamente as peças do diagnóstico antes de iniciar.");
        }
        jdbc.update("UPDATE reservas_estoque SET status = 'CONSUMIDA' WHERE reparo_id = ? AND status = 'RESERVADA'", reparoId);
    }
    public void liberar(UUID reparoId) {
        var reserva = reserva(reparoId);
        if (reserva.isEmpty() || reserva.get().status() != Reserva.Status.RESERVADA) return;
        for (var item : reserva.get().itens()) {
            if (jdbc.update("UPDATE pecas SET disponivel = disponivel + ? WHERE id = ? AND disponivel <= ?",
                    item.quantidade(), item.pecaId(), Integer.MAX_VALUE - item.quantidade()) != 1)
                throw new IllegalStateException("Limite de estoque excedido na devolução.");
        }
        jdbc.update("UPDATE reservas_estoque SET status = 'LIBERADA' WHERE reparo_id = ?", reparoId);
    }
}
