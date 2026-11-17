package br.com.techchallenge.mecanica.execution.domain.estoque;
import java.util.UUID;
public record ItemReserva(UUID pecaId, int quantidade) {
    public ItemReserva {
        if (pecaId == null || quantidade <= 0) throw new IllegalArgumentException("Peça e quantidade positiva são obrigatórias.");
    }
}
