CREATE INDEX ix_preregister_reservation_phone ON tb_preregister_reservation
    (expo_id, (regexp_replace(user_request::jsonb ->> 'phoneNumber', '[^0-9]', '', 'g')));
