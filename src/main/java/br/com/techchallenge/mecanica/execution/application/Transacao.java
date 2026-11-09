package br.com.techchallenge.mecanica.execution.application;
import java.util.function.Supplier;
public interface Transacao {
    <T> T executar(Supplier<T> operacao);
}
