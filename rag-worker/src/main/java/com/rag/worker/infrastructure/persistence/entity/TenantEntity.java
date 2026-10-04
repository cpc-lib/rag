package com.rag.worker.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

@Data
@TableName("tenant")
public class TenantEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String name;
    private Integer status;
    private Integer maxStorageMb;
    private Integer maxMqConcurrency;
    private Long maxLlmTokensMonth;
    private Integer maxSseConnections;
}
