CREATE TABLE decisoes_orcamento_recebidas (
    event_id UUID PRIMARY KEY,
    orcamento_id UUID NOT NULL UNIQUE,
    reparo_id UUID NOT NULL UNIQUE REFERENCES reparos(id),
    ordem_id UUID NOT NULL,
    aprovado BOOLEAN NOT NULL,
    recebido_em TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
