package br.com.techchallenge.mecanica.execution.application;
import java.util.Optional;
import java.util.UUID;
import br.com.techchallenge.mecanica.execution.domain.reparo.Reparo;
public interface ReparoGateway {
    record Registro(Reparo reparo, long versao) {}
    void inserir(Reparo reparo);
    java.util.List<Reparo> fila(int limite);
    Registro prepararCancelamento(UUID id, UUID ordemId, java.time.Instant agora);
    Optional<Registro> buscar(UUID id);
    void atualizar(Registro registro);
    void registrarCancelamento(Reparo reparo, java.time.Instant ocorridoEm);
}
