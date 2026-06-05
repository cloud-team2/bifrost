-- 로컬 테스트용 샘플 스키마(#90). user-postgres(testdb) 최초 기동 시 1회 실행.
-- 파이프라인 생성 마법사의 테이블 선택/스키마 조회와 CDC(source) 데모용.
CREATE TABLE IF NOT EXISTS public.orders (
    id          SERIAL PRIMARY KEY,
    customer    TEXT NOT NULL,
    amount      NUMERIC(10, 2) NOT NULL,
    status      TEXT NOT NULL DEFAULT 'pending',
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.customers (
    id      SERIAL PRIMARY KEY,
    name    TEXT NOT NULL,
    email   TEXT UNIQUE NOT NULL
);

INSERT INTO public.orders (customer, amount, status) VALUES
    ('alice', 12.50, 'paid'),
    ('bob', 99.00, 'pending'),
    ('carol', 4.20, 'paid');

INSERT INTO public.customers (name, email) VALUES
    ('alice', 'alice@example.com'),
    ('bob', 'bob@example.com');

-- CDC(Debezium) 데모를 위한 publication. debezium 유저는 컨테이너 superuser라 권한 충분.
-- (init 스크립트는 최초 1회만 실행되므로 중복 생성 우려 없음)
CREATE PUBLICATION dbz_publication FOR ALL TABLES;
