-- #226: a final delivery failure reported before any provider acceptance gets a response deadline of report time
-- + 6 hours (capped by the hard cutoff), so DELIVERY_FAILED offers expire like SENT ones instead of holding seats.
ALTER TABLE tb_promotion_offer DROP CONSTRAINT tb_promotion_offer_check2;
ALTER TABLE tb_promotion_offer ADD CONSTRAINT tb_promotion_offer_check2
    CHECK ((expires_at IS NULL) = (provider_accepted_at IS NULL)
        OR (expires_at IS NOT NULL AND provider_accepted_at IS NULL AND delivery_status = 'FAILED'));
