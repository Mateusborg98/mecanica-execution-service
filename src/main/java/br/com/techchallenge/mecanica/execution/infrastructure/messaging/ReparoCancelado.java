package br.com.techchallenge.mecanica.execution.infrastructure.messaging;
import java.time.Instant;
import java.util.UUID;
public record ReparoCancelado(int schemaVersion,String eventType,UUID eventId,UUID reparoId,UUID ordemId,Instant ocorridoEm) {}
