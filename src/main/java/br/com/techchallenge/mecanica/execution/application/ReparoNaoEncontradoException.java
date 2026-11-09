package br.com.techchallenge.mecanica.execution.application;
public class ReparoNaoEncontradoException extends RuntimeException {
    public ReparoNaoEncontradoException() { super("Reparo não encontrado."); }
}
