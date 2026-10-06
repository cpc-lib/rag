package com.rag.api.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
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
    /** 反向提示词（万相 negative_prompt，描述不希望出现的内容）。 */
    private String negativePrompt;
    private String model;
    private String size;
    private Long seed;
    private String objectKey;
    private String fileName;
    private Long fileSize;
    /** 文件内容 SHA-256（hex，Worker 异步计算）。 */
    private String sha256;
    private String status;
    private String errorMsg;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    /** 逻辑删除：0=正常 1=已删除。 */
    private Integer deleted;
}
