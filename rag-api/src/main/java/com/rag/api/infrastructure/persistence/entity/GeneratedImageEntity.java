package com.rag.api.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
}
