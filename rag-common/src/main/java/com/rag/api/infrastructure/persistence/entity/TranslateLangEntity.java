package com.rag.api.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 字幕翻译目标语言：租户级维护，语言名直接作为翻译提示词的目标语言传参。 */
@Data
@TableName("translate_lang")
public class TranslateLangEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    /** 语言显示名，如 简体中文/English/日本語。 */
    private String name;
    private Integer sortNo;
    private LocalDateTime createdAt;
    /** 逻辑删除：0=正常 1=已删除。 */
    private Integer deleted;
}
