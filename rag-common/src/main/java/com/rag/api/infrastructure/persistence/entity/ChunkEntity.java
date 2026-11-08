package com.rag.api.infrastructure.persistence.entity;

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
    /** AUTO自动/MANUAL人工/DELETED已删除 */
    private String status;
    /** PARENT/CHILD */
    private String chunkType;
    private String contentType;
    private Long parentChunkId;
    private String sectionTitle;
    private String sectionPath;
    /** PDF 版面坐标 x,y,width,height（pt），非 PDF 或无法定位时为空。 */
    private String bbox;
    /** 原文块标识，如 p3-b2 或 p3-b2~p3-b5（页-块，1 起）。 */
    private String sourceId;
    /** PDF 版面块语义类别：TITLE/PARAGRAPH/LIST/TABLE/IMAGE/CODE/FORMULA，非 PDF 为空。 */
    private String blockType;
    private String contentHash;
    /** 逻辑删除：0=正常 1=已删除。 */
    private Integer deleted;
}
