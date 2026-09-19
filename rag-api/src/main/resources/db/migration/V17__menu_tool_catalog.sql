-- 菜单与工具目录迁移到后端：前端不再硬编码菜单/工具清单，
-- sys_menu / sys_tool 成为产品级权威定义；tenant_menu 是功能菜单的租户总开关
-- （与工具的 tool_config 租户总开关对称）。

CREATE TABLE sys_menu (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    code              VARCHAR(50)  NOT NULL COMMENT '菜单码',
    name              VARCHAR(100) NOT NULL COMMENT '显示名称',
    path              VARCHAR(100) NOT NULL COMMENT '前端路由路径',
    icon              VARCHAR(50)  NULL COMMENT '图标标识',
    platform_visible  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '平台管理员可见',
    admin_visible     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '租户管理员可见',
    end_user          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '可授权给普通用户',
    sort              INT          NOT NULL DEFAULT 0,
    status            TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '产品级启用',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_menu_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='菜单目录';

INSERT INTO sys_menu
    (code, name, path, icon, platform_visible, admin_visible, end_user, sort) VALUES
    ('tenants',       '租户管理',   '/tenants',       'team',      1, 0, 0, 10),
    ('chat',          '智能问答',   '/chat',          'message',   0, 1, 1, 10),
    ('image-studio',  'AI 画图',    '/image-studio',  'picture',   0, 1, 1, 20),
    ('knowledge-bases','知识库管理','/knowledge-bases','book',     0, 1, 0, 30),
    ('users',         '用户管理',   '/users',         'team',      0, 1, 0, 40),
    ('model-config',  '模型参数',   '/model-config',  'setting',   0, 1, 0, 50),
    ('prompts',       '提示词管理', '/prompts',       'bulb',      0, 1, 0, 60),
    ('tool-config',   '功能配置',   '/tool-config',   'tool',      0, 1, 0, 70),
    ('quotas',        '配额用量',   '/quotas',        'dashboard', 0, 1, 0, 80);

CREATE TABLE sys_tool (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    code         VARCHAR(50)  NOT NULL COMMENT '工具码（个人授权用）',
    name         VARCHAR(100) NOT NULL COMMENT '显示名称',
    fn_name      VARCHAR(50)  NOT NULL COMMENT 'LLM function 名',
    description  VARCHAR(500) NOT NULL COMMENT '功能说明（配置页展示）',
    requires_key TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否需配置 API Key',
    sort         INT          NOT NULL DEFAULT 0,
    status       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '产品级启用',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='工具目录';

INSERT INTO sys_tool (code, name, fn_name, description, requires_key, sort) VALUES
    ('weather', '天气查询', 'query_weather',
     '识别气象意图与地域后，调用免费气象接口，将实时天气注入问答上下文。', 0, 10),
    ('tavily', '联网搜索', 'tavily_search',
     '用户询问最新资讯或需要联网信息时自动检索。', 1, 20);

-- 功能菜单租户总开关：存量租户默认开启全部可授权菜单。
CREATE TABLE tenant_menu (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id  VARCHAR(20)  NOT NULL,
    menu_code  VARCHAR(50)  NOT NULL,
    enabled    TINYINT(1)   NOT NULL DEFAULT 1,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_menu (tenant_id, menu_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='功能菜单租户总开关';

INSERT INTO tenant_menu (tenant_id, menu_code)
SELECT t.id, m.code
FROM tenant t
CROSS JOIN sys_menu m
WHERE m.end_user = 1
  AND NOT EXISTS (
      SELECT 1 FROM tenant_menu tm WHERE tm.tenant_id = t.id AND tm.menu_code = m.code
  );
