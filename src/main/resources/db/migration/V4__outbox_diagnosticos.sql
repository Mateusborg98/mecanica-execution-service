CREATE TABLE outbox_diagnosticos (
    event_id UUID PRIMARY KEY,
    reparo_id UUID NOT NULL UNIQUE REFERENCES diagnosticos(reparo_id),
    criado_em TIMESTAMP WITH TIME ZONE NOT NULL,
    publicado_em TIMESTAMP WITH TIME ZONE,
    proxima_tentativa TIMESTAMP WITH TIME ZONE NOT NULL,
    tentativas INTEGER NOT NULL DEFAULT 0,
    ultimo_erro VARCHAR(200)
);
CREATE INDEX idx_outbox_diagnosticos_pendentes ON outbox_diagnosticos(publicado_em, proxima_tentativa);
INSERT INTO outbox_diagnosticos(event_id, reparo_id, criado_em, proxima_tentativa)
SELECT reparo_id, reparo_id, criado_em, criado_em FROM diagnosticos;
