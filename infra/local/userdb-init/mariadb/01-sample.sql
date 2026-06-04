-- 로컬 테스트용 샘플 스키마(#90). user-mariadb(testdb) 최초 기동 시 1회 실행.
-- CDC sink 또는 source 데모용.
CREATE TABLE IF NOT EXISTS orders (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    customer    VARCHAR(100) NOT NULL,
    amount      DECIMAL(10, 2) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'pending',
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO orders (customer, amount, status) VALUES
    ('alice', 12.50, 'paid'),
    ('bob', 99.00, 'pending');
