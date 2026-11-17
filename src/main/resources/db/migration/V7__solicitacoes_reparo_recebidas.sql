CREATE TABLE solicitacoes_reparo_recebidas (
    event_id UUID PRIMARY KEY,
    ordem_id UUID NOT NULL UNIQUE,
    reparo_id UUID NOT NULL UNIQUE,
    ocorrido_em TIMESTAMP WITH TIME ZONE NOT NULL,
    processado BOOLEAN NOT NULL DEFAULT FALSE
);
