package br.com.techchallenge.mecanica.execution.domain.estoque;

import java.math.BigDecimal;
import java.util.UUID;

public record Peca(UUID id, String sku, String descricao, BigDecimal preco, int disponivel) {
    public Peca {
        if (id == null || sku == null || sku.isBlank() || sku.length() > 64
                || descricao == null || descricao.isBlank() || descricao.length() > 200
                || preco == null || preco.signum() <= 0 || preco.scale() > 2
                || preco.compareTo(new BigDecimal("9999999999.99")) > 0 || disponivel < 0) {
            throw new IllegalArgumentException("Peça inválida: informe SKU, descrição e preço positivo com até duas casas decimais.");
        }
        sku = sku.strip(); descricao = descricao.strip();
    }
}
