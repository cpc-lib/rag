-- 租户编码：对外展示与登录使用，非空且唯一；内部随机 id 保留不动。
ALTER TABLE tenant ADD COLUMN code VARCHAR(20) NULL COMMENT '租户编码（字母/数字/下划线，10-20位）';
-- 存量行回填：超管给固定编码，普通租户用 TENANT+内部id（唯一且长度合规）。
UPDATE tenant
SET code = CASE WHEN id = '000000' THEN 'SUPER_TENANT' ELSE CONCAT('TENANT', id) END;
ALTER TABLE tenant MODIFY COLUMN code VARCHAR(20) NOT NULL COMMENT '租户编码（字母/数字/下划线，10-20位）';
ALTER TABLE tenant ADD CONSTRAINT uk_tenant_code UNIQUE (code);
