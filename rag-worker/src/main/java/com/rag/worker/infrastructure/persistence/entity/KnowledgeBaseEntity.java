package com.rag.worker.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

@Data
@TableName("knowledge_base")
public class KnowledgeBaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 逻辑删除：0=正常 1=已删除。 */
    @TableLogic
    private Integer deleted;

    private String tenantId;
    private String name;
    private String description;
    private String milvusCollection;
    private String esIndex;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Integer parentChunkSize;
    private Integer childChunkSize;
    private Integer childOverlap;
    private String chunkStrategy;
    private String separators;
}
