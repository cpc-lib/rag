package com.rag.api.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("prompt_template")
public class PromptTemplateEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private Long kbId;
    /** 模板分类：NULL=知识库问答（兼容历史），SUBTITLE=字幕翻译。 */
    private String category;
    private String name;
    private String content;
    private Boolean isDefault;
    private java.time.LocalDateTime createdAt;
    private java.time.LocalDateTime updatedAt;
}
