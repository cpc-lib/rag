package com.rag.api.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.rag.api.common.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 多租户拦截器（设计文档 §13）：
 * - 普通租户请求自动追加 tenant_id = '当前租户'
 * - user_type=0 平台管理员 或 无登录上下文（登录接口）时放行（不拼接）
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new StringValue(TenantContext.require().tenantId());
            }

            @Override
            public boolean ignoreTable(String tableName) {
                TenantContext.Session session = TenantContext.get();
                if (session == null || session.isPlatformAdmin()) {
                    return true;
                }
                // tenant 表本身是租户注册表，不做租户过滤；
                // sys_menu / sys_tool 是产品级全局目录，不区分租户
                return tableName.equalsIgnoreCase("tenant")
                        || tableName.equalsIgnoreCase("sys_menu")
                        || tableName.equalsIgnoreCase("sys_tool");
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }
        }));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
