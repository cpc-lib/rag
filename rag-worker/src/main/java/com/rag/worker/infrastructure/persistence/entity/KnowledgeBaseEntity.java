package com.rag.worker.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("knowledge_base")
public class KnowledgeBaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

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
