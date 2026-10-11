CREATE TABLE tb_report_application_snapshot (
    id UUID PRIMARY KEY,
    expo_id UUID NOT NULL,
    snapshot_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    page_size INTEGER NOT NULL CHECK (page_size BETWEEN 1 AND 500),
    items JSONB NOT NULL CHECK (jsonb_typeof(items) = 'array' AND jsonb_array_length(items) <= 10000)
);

CREATE INDEX ix_report_application_snapshot_expiry ON tb_report_application_snapshot (expires_at);
