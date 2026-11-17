package br.com.techchallenge.mecanica.execution.application;
public class ConflitoReparoException extends RuntimeException {
    public ConflitoReparoException() { super("Reparo já cadastrado ou alterado por outra operação."); }
}
