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
    // Chamado apenas pelo futuro adaptador que confirmar a aprovação do orçamento.
    public Reparo liberar(UUID id, UUID orcamentoId) { return alterar(id, r -> r.liberar(orcamentoId)); }
    public Reparo iniciar(UUID id) { return alterar(id, r -> r.iniciar(Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS))); }
    public Reparo concluir(UUID id) { return alterar(id, r -> r.concluir(Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS))); }
    public Reparo cancelar(UUID id) { return alterar(id, Reparo::cancelar); }
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
