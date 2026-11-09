package br.com.techchallenge.mecanica.execution.application;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import br.com.techchallenge.mecanica.execution.domain.reparo.Reparo;
public class ReparoService {
    private final ReparoGateway gateway;
    private final Clock clock;
    private final EstoqueGateway estoque;
    private final Transacao tx;
    public ReparoService(ReparoGateway gateway, Clock clock, EstoqueGateway estoque, Transacao tx) {
        this.gateway = gateway; this.clock = clock; this.estoque = estoque; this.tx = tx;
    }
    public Reparo criar(UUID ordemId) {
        var reparo = new Reparo(ordemId, Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS)); gateway.inserir(reparo); return reparo;
    }
    public Reparo buscar(UUID id) { return registro(id).reparo(); }
    public java.util.List<Reparo> fila(int limite) {
        if (limite < 1 || limite > 100) throw new IllegalArgumentException("Limite deve estar entre 1 e 100.");
        return gateway.fila(limite);
    }
    public Reparo cancelarSolicitacao(UUID id, UUID ordemId) {
        if (id == null || ordemId == null) throw new IllegalArgumentException("Reparo e OS são obrigatórios.");
        return tx.executar(() -> {
            var agora = Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var registro = gateway.prepararCancelamento(id, ordemId, agora);
            registro.reparo().cancelar();
            gateway.atualizar(registro);
            estoque.liberar(id);
            gateway.registrarCancelamento(registro.reparo(), agora);
            return registro.reparo();
        });
    }
    // Chamado pelo adaptador de decisões confirmadas pelo Billing.
    public Reparo liberar(UUID id, UUID orcamentoId) { return alterar(id, r -> r.liberar(orcamentoId)); }
    public Reparo recusarOrcamento(UUID id, UUID orcamentoId) { return alterar(id, r -> r.recusarOrcamento(orcamentoId)); }
    public Reparo iniciar(UUID id) { return alterar(id, r -> r.iniciar(Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS))); }
    public Reparo concluir(UUID id) { return alterar(id, r -> r.concluir(Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS))); }
    public Reparo cancelar(UUID id) {
        return tx.executar(() -> {
            var reparo = alterar(id, Reparo::cancelar);
            gateway.registrarCancelamento(reparo, Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            return reparo;
        });
    }
    private ReparoGateway.Registro registro(UUID id) {
        return gateway.buscar(id).orElseThrow(ReparoNaoEncontradoException::new);
    }
    private Reparo alterar(UUID id, Consumer<Reparo> operacao) {
        return tx.executar(() -> {
            var registro = registro(id);
            operacao.accept(registro.reparo());
            gateway.atualizar(registro);
            switch (registro.reparo().getStatus()) {
                case EM_EXECUCAO -> estoque.consumir(id);
                case CANCELADO -> estoque.liberar(id);
                default -> { }
            }
            return registro.reparo();
        });
    }
}
