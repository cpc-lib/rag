package com.rag.api.infrastructure.persistence.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisParameterHandler;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.rag.api.infrastructure.persistence.entity.PromptTemplateEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 冒烟验证：不启动 Spring，直接走 MyBatis 执行 insert 时的真实参数处理链路
 * （MybatisParameterHandler.processParameter → TableInfo 闸门 → isWithInsertFill 闸门 → handler.insertFill），
 * 断言实体的 createdAt/updatedAt 被 MetaObjectHandler 填充。
 */
class MetaObjectHandlerSmokeTest {

    @Test
    void insertShouldFillTimestamps() {
        // 1. 模拟应用启动：MetaObjectHandler 装入 GlobalConfig（MybatisPlusAutoConfiguration 的 getBeanThen 等效）
        MybatisConfiguration configuration = new MybatisConfiguration();
        MetaObjectHandler handler = new RagPersistenceAutoConfiguration().ragMetaObjectHandler();
        GlobalConfigUtils.getGlobalConfig(configuration).setMetaObjectHandler(handler);

        // 2. 模拟 RagPersistenceAutoConfiguration 的 TableInfo 手动注册
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, PromptTemplateEntity.class);

        // 3. 构造一个 INSERT MappedStatement（与 XML 中 PromptTemplateMapper.insert 等效的最小形态）
        SqlSource sqlSource = new RawSqlSource(configuration,
                "INSERT INTO prompt_template (tenant_id, name, content, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                PromptTemplateEntity.class);
        MappedStatement ms = new MappedStatement.Builder(configuration, "test.insert", sqlSource, SqlCommandType.INSERT).build();

        // 4. 触发参数处理（与真实 insert 执行路径一致）
        PromptTemplateEntity entity = new PromptTemplateEntity();
        entity.setTenantId("000000");
        new MybatisParameterHandler(ms, entity, null);

        // 5. 断言时间字段已被填充
        assertNotNull(entity.getCreatedAt(), "createdAt 未被填充——TableInfo/isWithInsertFill 闸门未通过");
        assertNotNull(entity.getUpdatedAt(), "updatedAt 未被填充");
    }
}
