-- sys_user 上的 menu_codes / tool_codes（逗号分隔）规范化为关系表 user_feature，
-- feature_type 区分 MENU / TOOL。迁移存量数据后删除旧列，消除双存储。

CREATE TABLE user_feature (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id    VARCHAR(20)  NOT NULL,
    user_id      BIGINT       NOT NULL,
    feature_type VARCHAR(10)  NOT NULL COMMENT 'MENU / TOOL',
    code         VARCHAR(50)  NOT NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_feature (user_id, feature_type, code),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户功能授权（菜单/工具）';

-- 用递归 CTE 将逗号串拆成行（MySQL 8）；注意 MySQL 要求 CTE 位于 INSERT 之后
INSERT INTO user_feature (tenant_id, user_id, feature_type, code)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 20
),
exploded AS (
    SELECT u.id AS user_id, u.tenant_id, 'MENU' AS feature_type,
           TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(u.menu_codes, ',', seq.n), ',', -1)) AS code
    FROM sys_user u
    JOIN seq
      ON seq.n <= 1 + CHAR_LENGTH(u.menu_codes)
                    - CHAR_LENGTH(REPLACE(u.menu_codes, ',', ''))
    WHERE u.menu_codes IS NOT NULL AND u.menu_codes <> ''
    UNION ALL
    SELECT u.id, u.tenant_id, 'TOOL',
           TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(u.tool_codes, ',', seq.n), ',', -1))
    FROM sys_user u
    JOIN seq
      ON seq.n <= 1 + CHAR_LENGTH(u.tool_codes)
                    - CHAR_LENGTH(REPLACE(u.tool_codes, ',', ''))
    WHERE u.tool_codes IS NOT NULL AND u.tool_codes <> ''
)
SELECT tenant_id, user_id, feature_type, code
FROM exploded
WHERE code <> '';

ALTER TABLE sys_user
    DROP COLUMN menu_codes,
    DROP COLUMN tool_codes;
