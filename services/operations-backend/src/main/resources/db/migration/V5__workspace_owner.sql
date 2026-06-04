-- 소유 기반 다중 워크스페이스(#72): 한 사용자가 여러 workspace를 소유/선택할 수 있게
-- tenants에 owner_user_id를 추가한다. add-only 마이그레이션(V1~V4 불변).
-- 교차 FK 규칙(todo.md)에 따라 users(id)로의 FK 제약은 선언하지 않고 uuid 컬럼 + 인덱스만 둔다.
ALTER TABLE tenants ADD COLUMN owner_user_id UUID;

-- 기존 데이터 백필: register 흐름으로 생성된 workspace의 소유자는 그 workspace에 속한 사용자다.
UPDATE tenants t
SET owner_user_id = (
    SELECT u.id FROM users u
    WHERE u.tenant_id = t.id
    ORDER BY u.created_at
    LIMIT 1
)
WHERE owner_user_id IS NULL;

CREATE INDEX idx_tenants_owner ON tenants(owner_user_id);
