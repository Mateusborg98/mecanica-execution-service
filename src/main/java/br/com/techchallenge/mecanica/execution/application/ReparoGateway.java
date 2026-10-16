package br.com.techchallenge.mecanica.execution.application;
import java.util.Optional;
import java.util.UUID;
import br.com.techchallenge.mecanica.execution.domain.reparo.Reparo;
public interface ReparoGateway {
    record Registro(Reparo reparo, long versao) {}
    void inserir(Reparo reparo);
    Optional<Registro> buscar(UUID id);
    void atualizar(Registro registro);
}
