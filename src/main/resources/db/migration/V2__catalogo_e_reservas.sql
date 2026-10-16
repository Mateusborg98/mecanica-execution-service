CREATE TABLE pecas (
    id UUID PRIMARY KEY,
    sku VARCHAR(64) NOT NULL UNIQUE,
    descricao VARCHAR(200) NOT NULL,
    preco NUMERIC(12,2) NOT NULL CHECK (preco > 0),
    disponivel INTEGER NOT NULL DEFAULT 0 CHECK (disponivel >= 0)
);
CREATE TABLE entradas_estoque (
    id UUID PRIMARY KEY,
    peca_id UUID NOT NULL REFERENCES pecas(id),
    quantidade INTEGER NOT NULL CHECK (quantidade > 0)
);
CREATE TABLE reservas_estoque (
    reparo_id UUID PRIMARY KEY REFERENCES reparos(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RESERVADA', 'CONSUMIDA', 'LIBERADA'))
);
CREATE TABLE itens_reserva (
    reparo_id UUID NOT NULL REFERENCES reservas_estoque(reparo_id),
    peca_id UUID NOT NULL REFERENCES pecas(id),
    quantidade INTEGER NOT NULL CHECK (quantidade > 0),
    PRIMARY KEY (reparo_id, peca_id)
);
