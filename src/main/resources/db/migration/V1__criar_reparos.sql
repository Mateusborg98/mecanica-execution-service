CREATE TABLE reparos (
    id UUID PRIMARY KEY,
    ordem_id UUID NOT NULL UNIQUE,
    orcamento_id UUID,
    status VARCHAR(32) NOT NULL CHECK (status IN ('AGUARDANDO_APROVACAO','NA_FILA','EM_EXECUCAO','CONCLUIDO','CANCELADO')),
    criado_em TIMESTAMP WITH TIME ZONE NOT NULL,
    iniciado_em TIMESTAMP WITH TIME ZONE,
    concluido_em TIMESTAMP WITH TIME ZONE,
    versao BIGINT NOT NULL DEFAULT 0,
    CHECK (iniciado_em IS NULL OR iniciado_em >= criado_em),
    CHECK (concluido_em IS NULL OR (iniciado_em IS NOT NULL AND concluido_em >= iniciado_em))
);
