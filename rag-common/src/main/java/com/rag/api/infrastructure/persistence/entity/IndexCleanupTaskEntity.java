package com.rag.api.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 索引删除补偿任务：文档删除时 ES/Milvus 清理失败落库，由 Worker 巡检排空。 */
@Data
@TableName("index_cleanup_task")
public class IndexCleanupTaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private Long kbId;
    private Long documentId;
    /** ES / MILVUS / BOTH */
    private String target;
    /** PENDING/DONE/FAILED */
    private String status;
    private Integer attempts;
    private String errorMsg;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    /** 逻辑删除：0=正常 1=已删除。 */
    private Integer deleted;
}
