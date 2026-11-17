package br.com.techchallenge.mecanica.execution.domain.diagnostico;
import java.math.BigDecimal;
import java.util.UUID;
public record Servico(UUID id, String codigo, String descricao, BigDecimal preco) {
    public Servico {
        if (id == null || codigo == null || codigo.isBlank() || codigo.length() > 64
                || descricao == null || descricao.isBlank() || descricao.length() > 200
                || preco == null || preco.signum() <= 0 || preco.scale() > 2
                || preco.compareTo(new BigDecimal("9999999999.99")) > 0)
            throw new IllegalArgumentException("Serviço inválido: código, descrição e preço positivo com até duas casas são obrigatórios.");
        codigo = codigo.strip(); descricao = descricao.strip();
    }
}
