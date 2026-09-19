package com.rag.worker.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("document")
public class DocumentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;
    private String tenantId;
    private String fileName;
    private String objectKey;
    private Long fileSize;
    private String mimeType;
    private String status;
    private Integer progress;
    private Integer pageCount;
    private String warning;
    private String errorMsg;
}
