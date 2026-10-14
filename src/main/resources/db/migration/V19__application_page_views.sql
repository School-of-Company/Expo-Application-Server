-- Application screen views (#108). Visitors are stored only as a per-expo, per-day salted hash of a client-generated UUID.
CREATE TABLE tb_application_page_view_daily (
    expo_id UUID NOT NULL,
    view_date DATE NOT NULL,
    page_views BIGINT NOT NULL DEFAULT 0,
    unique_visitors BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (expo_id, view_date)
);
CREATE TABLE tb_application_page_visitor (
    expo_id UUID NOT NULL,
    view_date DATE NOT NULL,
    visitor_hash BYTEA NOT NULL,
    PRIMARY KEY (expo_id, view_date, visitor_hash)
);
