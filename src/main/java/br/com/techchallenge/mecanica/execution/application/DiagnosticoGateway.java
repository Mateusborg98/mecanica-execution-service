package br.com.techchallenge.mecanica.execution.application;
import java.util.*;
import br.com.techchallenge.mecanica.execution.domain.diagnostico.*;
public interface DiagnosticoGateway {
    Servico criarServico(Servico servico);
    Servico buscarServico(UUID id);
    Diagnostico registrar(UUID reparoId, String observacoes, List<Diagnostico.Selecao> itens);
    Diagnostico buscar(UUID reparoId);
}
