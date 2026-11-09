package br.com.techchallenge.mecanica.execution.infrastructure.messaging;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record DiagnosticoRegistrado(int schemaVersion, String eventType, UUID eventId, UUID reparoId,
        UUID ordemId, Instant ocorridoEm, List<Item> itens) {
    public record Item(String tipo, UUID referenciaId, String descricao, int quantidade, BigDecimal valorUnitario) {}
}
