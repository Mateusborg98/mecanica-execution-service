package br.com.techchallenge.mecanica.execution.application;
import java.math.BigDecimal;
import java.util.*;
import br.com.techchallenge.mecanica.execution.domain.estoque.*;
public class EstoqueService {
    private final EstoqueGateway gateway;
    public EstoqueService(EstoqueGateway gateway) { this.gateway = gateway; }
    public Peca criar(String sku, String descricao, BigDecimal preco) {
        return gateway.criar(new Peca(UUID.randomUUID(), sku, descricao, preco, 0));
    }
    public Peca buscar(UUID id) { return gateway.buscar(id); }
    public Peca entrada(UUID movimentoId, UUID pecaId, int quantidade) {
        if (movimentoId == null || pecaId == null || quantidade <= 0)
            throw new IllegalArgumentException("Movimento, peça e quantidade positiva são obrigatórios.");
        return gateway.entrada(movimentoId, pecaId, quantidade);
    }
    public Reserva reservar(UUID reparoId, List<ItemReserva> itens) {
        if (reparoId == null || itens == null || itens.isEmpty() || itens.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Informe o reparo e os itens da reserva.");
        var ordenados = itens.stream().sorted(Comparator.comparing(i -> i.pecaId().toString())).toList();
        if (ordenados.stream().map(ItemReserva::pecaId).distinct().count() != itens.size())
            throw new IllegalArgumentException("Não repita uma peça na mesma reserva.");
        return gateway.reservar(reparoId, ordenados);
    }
    public Reserva buscarReserva(UUID reparoId) { return gateway.buscarReserva(reparoId); }
}
