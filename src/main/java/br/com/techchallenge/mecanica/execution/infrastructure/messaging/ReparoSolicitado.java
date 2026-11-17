package br.com.techchallenge.mecanica.execution.infrastructure.messaging;
import java.time.Instant;
import java.util.UUID;
public record ReparoSolicitado(int schemaVersion, String eventType, UUID eventId,
        UUID ordemId, UUID reparoId, Instant ocorridoEm) {}
