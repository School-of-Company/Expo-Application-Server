-- #297: 사전등록을 취소한 현장등록(FIELD) 참가자의 입장 QR을 닫는 표시다. 현장 재등록 요청이 처리되면 지운다.
CREATE TABLE tb_field_qr_revoked (
    expo_id UUID NOT NULL,
    participant_id BIGINT NOT NULL,
    PRIMARY KEY (expo_id, participant_id)
);
