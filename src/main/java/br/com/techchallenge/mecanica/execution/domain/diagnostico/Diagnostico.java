package br.com.techchallenge.mecanica.execution.domain.diagnostico;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record Diagnostico(UUID reparoId, UUID ordemId, String observacoes, Instant criadoEm, List<Item> itens) {
    public enum Tipo { PECA, SERVICO }
    public record Selecao(Tipo tipo, UUID referenciaId, int quantidade) {
        public Selecao {
            if (tipo == null || referenciaId == null || quantidade <= 0)
                throw new IllegalArgumentException("Informe tipo, referência e quantidade positiva.");
        }
    }
    public record Item(Tipo tipo, UUID referenciaId, String descricao, int quantidade, BigDecimal valorUnitario) {
        public BigDecimal subtotal() { return valorUnitario.multiply(BigDecimal.valueOf(quantidade)); }
        public Selecao selecao() { return new Selecao(tipo, referenciaId, quantidade); }
    }
    public Diagnostico { itens = List.copyOf(itens); }
    public BigDecimal total() { return itens.stream().map(Item::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add); }
}
