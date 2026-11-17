CREATE TABLE outbox_reparos_concluidos (
    event_id UUID PRIMARY KEY,
    reparo_id UUID NOT NULL UNIQUE REFERENCES reparos(id),
    ordem_id UUID NOT NULL,
    orcamento_id UUID NOT NULL,
    ocorrido_em TIMESTAMP WITH TIME ZONE NOT NULL,
    publicado_em TIMESTAMP WITH TIME ZONE,
    proxima_tentativa TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    tentativas INTEGER NOT NULL DEFAULT 0,
    ultimo_erro VARCHAR(200)
);
CREATE INDEX idx_outbox_conclusoes_pendentes ON outbox_reparos_concluidos(publicado_em, proxima_tentativa);
-- Historical repairs are not replayed automatically: their budgets may lack correlation.
