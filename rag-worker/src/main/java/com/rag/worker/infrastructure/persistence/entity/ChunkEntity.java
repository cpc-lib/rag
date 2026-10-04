package com.rag.worker.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

@Data
@TableName("chunk")
public class ChunkEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 逻辑删除：0=正常 1=已删除。 */
    @TableLogic
    private Integer deleted;

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
