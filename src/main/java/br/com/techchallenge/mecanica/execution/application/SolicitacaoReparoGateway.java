package br.com.techchallenge.mecanica.execution.application;
import java.time.Instant;
import java.util.UUID;
public interface SolicitacaoReparoGateway { void receber(UUID ordemId,UUID reparoId,Instant ocorridoEm); }
