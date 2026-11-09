package br.com.techchallenge.mecanica.execution.application;
import java.util.UUID;
public interface DecisaoOrcamentoGateway {
    void aplicar(UUID eventId, UUID orcamentoId, UUID reparoId, UUID ordemId, boolean aprovado);
}
