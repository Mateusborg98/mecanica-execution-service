CREATE TABLE servicos (
    id UUID PRIMARY KEY,
    codigo VARCHAR(64) NOT NULL UNIQUE,
    descricao VARCHAR(200) NOT NULL,
    preco NUMERIC(12,2) NOT NULL CHECK (preco > 0)
);
CREATE TABLE diagnosticos (
    reparo_id UUID PRIMARY KEY REFERENCES reparos(id),
    observacoes VARCHAR(2000) NOT NULL,
    criado_em TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE itens_diagnostico (
    reparo_id UUID NOT NULL REFERENCES diagnosticos(reparo_id),
    posicao INTEGER NOT NULL CHECK (posicao >= 0),
    tipo VARCHAR(8) NOT NULL CHECK (tipo IN ('PECA', 'SERVICO')),
    referencia_id UUID NOT NULL,
    descricao VARCHAR(200) NOT NULL,
    quantidade INTEGER NOT NULL CHECK (quantidade > 0),
    valor_unitario NUMERIC(12,2) NOT NULL CHECK (valor_unitario > 0),
    PRIMARY KEY (reparo_id, posicao),
    UNIQUE (reparo_id, tipo, referencia_id)
);
