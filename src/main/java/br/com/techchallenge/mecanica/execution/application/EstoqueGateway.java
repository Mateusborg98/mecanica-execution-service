package br.com.techchallenge.mecanica.execution.application;
import java.util.List;
import java.util.UUID;
import br.com.techchallenge.mecanica.execution.domain.estoque.*;
public interface EstoqueGateway {
    Peca criar(Peca peca);
    Peca buscar(UUID id);
    Peca entrada(UUID movimentoId, UUID pecaId, int quantidade);
    Reserva reservar(UUID reparoId, List<ItemReserva> itens);
    Reserva buscarReserva(UUID reparoId);
    void consumir(UUID reparoId);
    void liberar(UUID reparoId);
}
