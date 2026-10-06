package com.rag.api.infrastructure.persistence.config;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.time.LocalDateTime;

/**
 * 持久层字段自动填充：insert 补 createdAt/updatedAt，update 刷新 updatedAt。
 * <p>
 * MyBatis-Plus 的填充链路有两道闸门（反编译 mybatis-plus-core 3.5.7 MybatisParameterHandler 确认）：
 * 1. processParameter 先查 TableInfoHelper.getTableInfo(实体类)，为 null 直接 return ——
 *    TableInfo 只在解析 extends BaseMapper 的 Mapper 时注册，本项目 Mapper 均为普通接口，故启动时在此手动注册；
 * 2. 仅当 tableInfo.isWithInsertFill()（实体字段带 @TableField(fill = ...)）才回调 insertFill ——
 *    实体 createdAt/updatedAt 已加 fill 注解。
 * 填充本体用判空 setValue（非 strictInsertFill，避免再次依赖 TableInfo 语义）。
 * 注册走 AutoConfiguration.imports（而非 @Component），保证 rag-worker（组件扫描不到 com.rag.api 包）同样生效。
 */
@Slf4j
@AutoConfiguration
public class RagPersistenceAutoConfiguration {

    @Bean
    public MetaObjectHandler ragMetaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                fillIfNull(metaObject, "createdAt", now);
                fillIfNull(metaObject, "updatedAt", now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                fillIfNull(metaObject, "updatedAt", LocalDateTime.now());
            }

            private void fillIfNull(MetaObject metaObject, String field, Object value) {
                if (metaObject.hasSetter(field) && metaObject.getValue(field) == null) {
                    metaObject.setValue(field, value);
                }
            }
        };
    }

    /**
     * 启动时把 rag-common 全部实体注册进 TableInfoHelper（普通接口 Mapper 不会触发注册）。
     * SmartInitializingSingleton 在所有单例实例化后、ApplicationRunner（如 DataInitializer 种子数据）之前执行，
     * 保证首个业务 insert 发生前 TableInfo 已就绪；initTableInfo 本身幂等。
     */
    @Bean
    public SmartInitializingSingleton ragTableInfoInitializer(SqlSessionFactory sqlSessionFactory) {
        return () -> {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(sqlSessionFactory.getConfiguration(), "");
            ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(TableName.class));
            int count = 0;
            for (BeanDefinition bd : scanner.findCandidateComponents("com.rag.api.infrastructure.persistence.entity")) {
                try {
                    TableInfoHelper.initTableInfo(assistant, Class.forName(bd.getBeanClassName()));
                    count++;
                } catch (Exception e) {
                    log.warn("TableInfo 注册失败 {}: {}", bd.getBeanClassName(), e.getMessage());
                }
            }
            log.info("TableInfo 手动注册完成，共 {} 个实体（MetaObjectHandler 自动填充已就绪）", count);
        };
    }
}
