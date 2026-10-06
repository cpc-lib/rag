package com.rag.api.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tenant")
public class TenantEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 对外租户编码（字母/数字/下划线，10-20位，创建后不可改） */
    private String code;

    private String name;

    /** 1启用 0停用 */
    private Integer status;

    private Integer maxStorageMb;
    private Integer maxMqConcurrency;
    private Long maxLlmTokensMonth;
    private Integer maxSseConnections;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 逻辑删除：0=正常 1=已删除。 */
    private Integer deleted;
}
