-- 普通用户功能菜单按用户授权（chat / image-studio），逗号分隔；管理员菜单仍按角色固定。
ALTER TABLE sys_user ADD COLUMN menu_codes VARCHAR(100) NULL COMMENT '已授权功能菜单码，逗号分隔';
UPDATE sys_user SET menu_codes = 'chat,image-studio' WHERE user_type = 2;

-- 用户-知识库授权关系：普通用户仅可访问被授权的知识库。
CREATE TABLE user_kb (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id  VARCHAR(20)  NOT NULL,
    user_id    BIGINT       NOT NULL,
    kb_id      BIGINT       NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_kb (user_id, kb_id),
    KEY idx_tenant_user (tenant_id, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户知识库授权';
