package br.com.techchallenge.mecanica.execution.infrastructure.messaging;
import java.time.Instant;
import java.util.UUID;
public record ReparoConcluido(int schemaVersion, String eventType, UUID eventId, UUID reparoId,
        UUID ordemId, UUID orcamentoId, Instant ocorridoEm) { }
