ALTER TABLE tenants ADD COLUMN timezone VARCHAR(50) NULL;

CREATE TABLE project_member (
    workspace_id UUID NOT NULL,
    user_id      UUID NOT NULL,
    role         VARCHAR(20) NOT NULL CHECK (role IN ('OWNER','ADMIN','MEMBER')),
    joined_at    TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id)
);

CREATE INDEX idx_project_member_user ON project_member(user_id);

INSERT INTO project_member (workspace_id, user_id, role)
SELECT id, owner_user_id, 'OWNER'
FROM tenants
WHERE owner_user_id IS NOT NULL;
