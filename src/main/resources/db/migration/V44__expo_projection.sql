-- Expo `expo.expo.changed` 로컬 프로젝션(docs/expo-projection.md). Application이 Expo에서 읽는 값 중 정원·신청 판정에 쓰지 않는 것만 둔다.
-- version은 Expo 아웃박스 sequence다. tombstone 뒤에도 행을 남겨 늦게 온 옛 메시지로 되살리지 않는다.
CREATE TABLE tb_expo_projection (
    expo_id uuid PRIMARY KEY,
    version bigint NOT NULL CHECK (version > 0),
    event_id uuid NOT NULL,
    deleted boolean NOT NULL,
    title text,
    started_day varchar(32),
    finished_day varchar(32),
    phone_verification_required boolean,
    phone_verification_revision bigint CHECK (phone_verification_revision >= 0),
    deleting boolean,
    applied_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (
        deleted OR (title IS NOT NULL AND started_day IS NOT NULL AND finished_day IS NOT NULL AND
            phone_verification_required IS NOT NULL AND phone_verification_revision IS NOT NULL AND deleting IS NOT NULL)
    )
);

CREATE TABLE tb_expo_projection_session (
    expo_id uuid NOT NULL REFERENCES tb_expo_projection(expo_id) ON DELETE CASCADE,
    session_id bigint NOT NULL CHECK (session_id > 0),
    cancel_ended_at timestamptz,
    PRIMARY KEY (expo_id, session_id)
);
