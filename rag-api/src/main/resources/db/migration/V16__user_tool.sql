-- 工具按用户授权：租户总开关之内，再控制某个普通用户能否使用具体工具。
ALTER TABLE sys_user ADD COLUMN tool_codes VARCHAR(100) NULL COMMENT '已授权工具码，逗号分隔（weather/tavily）';

-- 存量普通用户按租户当前已开启的工具回填，保持上线前的使用状态。
UPDATE sys_user u
JOIN tool_config tc ON tc.tenant_id = u.tenant_id
SET u.tool_codes = CONCAT_WS(',',
        IF(tc.weather_enabled = 1, 'weather', NULL),
        IF(tc.tavily_enabled = 1, 'tavily', NULL))
WHERE u.user_type = 2;
