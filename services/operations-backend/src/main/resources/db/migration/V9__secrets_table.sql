-- DB 기반 SecretStore (#106). datasource 자격증명을 metadb에 영속한다.
-- secret_ref는 SecretRefNaming이 생성하는 DNS-safe 식별자(최대 63자).
-- credential_json은 {"user":"...","password":"..."} 형태의 JSON — 내부 metadb 전용.
CREATE TABLE secrets (
    secret_ref      VARCHAR(63)  PRIMARY KEY,
    credential_json TEXT         NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT now()
);
