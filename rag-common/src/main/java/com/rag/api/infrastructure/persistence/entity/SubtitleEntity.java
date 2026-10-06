package com.rag.api.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("subtitle")
public class SubtitleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private Long userId;
    private String originalName;
    private String sourceLang;
    private String targetLang;
    private String srtContent;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 逻辑删除：0=正常 1=已删除。 */
    private Integer deleted;
}
