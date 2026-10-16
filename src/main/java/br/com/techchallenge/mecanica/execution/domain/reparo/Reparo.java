package br.com.techchallenge.mecanica.execution.domain.reparo;

import java.time.Instant;
import java.util.UUID;

public final class Reparo {
    private final UUID id;
    private final UUID ordemId;
    private final Instant criadoEm;
    private UUID orcamentoId;
    private StatusReparo status = StatusReparo.AGUARDANDO_APROVACAO;
    private Instant iniciadoEm;
    private Instant concluidoEm;

    public Reparo(UUID ordemId, Instant criadoEm) {
        if (ordemId == null || criadoEm == null) throw new IllegalArgumentException("Ordem e data são obrigatórias.");
        this.id = UUID.randomUUID(); this.ordemId = ordemId; this.criadoEm = criadoEm;
    }
    public static Reparo reconstituir(UUID id, UUID ordemId, Instant criadoEm, UUID orcamentoId,
            StatusReparo status, Instant iniciadoEm, Instant concluidoEm) {
        return new Reparo(id, ordemId, criadoEm, orcamentoId, status, iniciadoEm, concluidoEm);
    }
    private Reparo(UUID id, UUID ordemId, Instant criadoEm, UUID orcamentoId,
            StatusReparo status, Instant iniciadoEm, Instant concluidoEm) {
        this.id = java.util.Objects.requireNonNull(id);
        this.ordemId = java.util.Objects.requireNonNull(ordemId);
        this.criadoEm = java.util.Objects.requireNonNull(criadoEm);
        this.status = java.util.Objects.requireNonNull(status);
        this.orcamentoId = orcamentoId; this.iniciadoEm = iniciadoEm; this.concluidoEm = concluidoEm;
    }
    // Recebe a aprovação confirmada pelo contrato de integração, não uma intenção de pagamento.
    public void liberar(UUID orcamentoId) {
        if (orcamentoId == null) throw new IllegalArgumentException("Orçamento é obrigatório.");
        exigir(StatusReparo.AGUARDANDO_APROVACAO);
        this.orcamentoId = orcamentoId; this.status = StatusReparo.NA_FILA;
    }
    public void recusarOrcamento(UUID orcamentoId) {
        if (orcamentoId == null) throw new IllegalArgumentException("Orçamento é obrigatório.");
        if (status != StatusReparo.AGUARDANDO_APROVACAO && status != StatusReparo.CANCELADO)
            throw new IllegalStateException("Recusa incompatível com o estado do reparo.");
        this.orcamentoId = orcamentoId; this.status = StatusReparo.CANCELADO;
    }
    public void iniciar(Instant agora) {
        exigir(StatusReparo.NA_FILA); validarData(agora, criadoEm);
        iniciadoEm = agora; status = StatusReparo.EM_EXECUCAO;
    }
    public void concluir(Instant agora) {
        exigir(StatusReparo.EM_EXECUCAO); validarData(agora, iniciadoEm);
        concluidoEm = agora; status = StatusReparo.CONCLUIDO;
    }
    public void cancelar() {
        if (status != StatusReparo.AGUARDANDO_APROVACAO && status != StatusReparo.NA_FILA)
            throw new IllegalStateException("Só é possível cancelar antes de iniciar o reparo.");
        status = StatusReparo.CANCELADO;
    }
    private void exigir(StatusReparo esperado) {
        if (status != esperado) throw new IllegalStateException("Estado inválido para a operação: " + status);
    }
    private void validarData(Instant agora, Instant anterior) {
        if (agora == null || agora.isBefore(anterior)) throw new IllegalArgumentException("Data inválida para a operação.");
    }
    public UUID getId() { return id; }
    public UUID getOrdemId() { return ordemId; }
    public UUID getOrcamentoId() { return orcamentoId; }
    public Instant getCriadoEm() { return criadoEm; }
    public StatusReparo getStatus() { return status; }
    public Instant getIniciadoEm() { return iniciadoEm; }
    public Instant getConcluidoEm() { return concluidoEm; }
}
