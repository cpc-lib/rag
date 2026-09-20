package com.rag.worker.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("chunk")
public class ChunkEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;
    private Long documentId;
    private String tenantId;
    private Integer seq;
    private String content;
    private Integer page;
    private String status;
    /** PARENT/CHILD */
    private String chunkType;
    private String contentType;
    private Long parentChunkId;
    private String sectionTitle;
    private String sectionPath;
    private String contentHash;
}
