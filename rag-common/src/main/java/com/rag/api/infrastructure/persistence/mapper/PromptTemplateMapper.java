package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.PromptTemplateEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface PromptTemplateMapper {

    PromptTemplateEntity selectById(@Param("id") Long id);

    List<PromptTemplateEntity> selectByIds(@Param("ids") List<Long> ids);

    List<PromptTemplateEntity> selectByTenantIdAndCategoryIsNull(@Param("tenantId") String tenantId);

    List<PromptTemplateEntity> selectByKbId(@Param("kbId") Long kbId);

    List<PromptTemplateEntity> selectByKbIdAndIds(@Param("kbId") Long kbId, @Param("ids") List<Long> ids);

    PromptTemplateEntity selectDefaultByTenantIdAndKbId(@Param("tenantId") String tenantId, @Param("kbId") Long kbId);

    PromptTemplateEntity selectByTenantIdAndCategory(@Param("tenantId") String tenantId, @Param("category") String category);

    List<PromptTemplateEntity> selectByKbIdAndIsDefault(@Param("kbId") Long kbId, @Param("isDefault") Boolean isDefault);

    List<Long> selectIdsByKbId(@Param("kbId") Long kbId);

    int insert(PromptTemplateEntity entity);

    int updateById(PromptTemplateEntity entity);

    int deleteById(@Param("id") Long id);

    int logicDeleteByKbId(@Param("kbId") Long kbId);
}
