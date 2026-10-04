package com.rag.api.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("generated_image")
public class GeneratedImageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private Long userId;
    private String prompt;
    private String model;
    private String size;
    private Long seed;
    private String objectKey;
    private String fileName;
    private Long fileSize;
    private String status;
    private String errorMsg;
    private LocalDateTime createdAt;
    /** 逻辑删除：0=正常 1=已删除。 */
    @TableLogic
    private Integer deleted;
}
