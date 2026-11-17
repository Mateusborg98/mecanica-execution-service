package br.com.techchallenge.mecanica.execution.application;
import java.math.BigDecimal;
import java.util.*;
import br.com.techchallenge.mecanica.execution.domain.diagnostico.*;
public class DiagnosticoService {
    private final DiagnosticoGateway gateway;
    public DiagnosticoService(DiagnosticoGateway gateway) { this.gateway = gateway; }
    public Servico criarServico(String codigo, String descricao, BigDecimal preco) {
        return gateway.criarServico(new Servico(UUID.randomUUID(), codigo, descricao, preco));
    }
    public Servico buscarServico(UUID id) { return gateway.buscarServico(id); }
    public Diagnostico buscar(UUID reparoId) { return gateway.buscar(reparoId); }
    public Diagnostico registrar(UUID reparoId, String observacoes, List<Diagnostico.Selecao> itens) {
        if (reparoId == null || observacoes == null || observacoes.isBlank() || observacoes.length() > 2000
                || itens == null || itens.isEmpty() || itens.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Informe reparo, observações e itens do diagnóstico.");
        if (itens.stream().map(i -> i.tipo() + ":" + i.referenciaId()).distinct().count() != itens.size())
            throw new IllegalArgumentException("Não repita um item no diagnóstico.");
        var ordenados = itens.stream().sorted(Comparator.comparing((Diagnostico.Selecao i) -> i.tipo().name())
                .thenComparing(i -> i.referenciaId().toString())).toList();
        return gateway.registrar(reparoId, observacoes.strip(), ordenados);
    }
}
