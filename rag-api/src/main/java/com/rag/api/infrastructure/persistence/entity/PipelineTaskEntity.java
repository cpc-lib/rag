package com.rag.api.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

@Data
@TableName("pipeline_task")
public class PipelineTaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private Long kbId;
    private Long documentId;
    /** PARSE/REINDEX */
    private String type;
    /** PENDING/RUNNING/SUCCESS/FAILED */
    private String status;
    private Integer retryCount;
    private String payload;
    private String errorMsg;
    /** 逻辑删除：0=正常 1=已删除。 */
    @TableLogic
    private Integer deleted;
}
