package br.com.techchallenge.mecanica.execution.domain.estoque;
import java.util.List;
import java.util.UUID;
public record Reserva(UUID reparoId, Status status, List<ItemReserva> itens) {
    public enum Status { RESERVADA, CONSUMIDA, LIBERADA }
    public Reserva { itens = List.copyOf(itens); }
}
