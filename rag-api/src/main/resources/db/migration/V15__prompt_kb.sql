-- 提示词模板全部绑定知识库；每个知识库拥有自己的模板集合。
ALTER TABLE prompt_template ADD COLUMN kb_id BIGINT AFTER tenant_id;

-- 1) 为每个知识库克隆其租户的旧默认模板。
INSERT INTO prompt_template (tenant_id, kb_id, name, content, is_default, created_at, updated_at)
SELECT k.tenant_id, k.id, p.name, p.content, 1, NOW(), NOW()
FROM knowledge_base k
JOIN prompt_template p
  ON p.tenant_id = k.tenant_id AND p.is_default = 1 AND p.kb_id IS NULL;

-- 2) 兜底：租户没有旧默认模板的知识库，补一份标准默认模板。
INSERT INTO prompt_template (tenant_id, kb_id, name, content, is_default, created_at, updated_at)
SELECT k.tenant_id, k.id, '默认知识库问答模板',
'你是企业知识库智能问答助手。请优先依据【资料】回答用户问题；引用资料时在句末标注角标序号，如 [1]。【外部信息】仅作补充参考。若资料与问题无关且无外部信息，请如实说明未找到相关内容。

【资料】
{{资料}}
{{外部信息}}', 1, NOW(), NOW()
FROM knowledge_base k
WHERE NOT EXISTS (SELECT 1 FROM prompt_template p WHERE p.kb_id = k.id);

-- 3) 旧的租户级全局模板已无用，删除。
DELETE FROM prompt_template WHERE kb_id IS NULL;
ALTER TABLE prompt_template MODIFY COLUMN kb_id BIGINT NOT NULL;
ALTER TABLE prompt_template ADD INDEX idx_kb (kb_id);

-- 用户提示词授权：仅被授权的模板可在问答页选择。
CREATE TABLE user_prompt (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id  VARCHAR(20) NOT NULL,
    user_id    BIGINT      NOT NULL,
    prompt_id  BIGINT      NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_prompt (user_id, prompt_id),
    KEY idx_tenant_user (tenant_id, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户提示词模板授权';
