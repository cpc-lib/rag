package com.rag.worker.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("model")
public class ModelEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 逻辑删除：0=正常 1=已删除。 */
    @TableLogic
    private Integer deleted;

    private String tenantId;
    private String name;
    /** CHAT / VISION / EMBEDDING / IMAGE */
    private String type;
    private String baseUrl;
    private String apiKey;
    private String model;
    private BigDecimal temperature;
    private BigDecimal topP;
    private Integer maxTokens;
    private Integer embeddingDim;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
