package com.rag.api.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
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
    /** 文件内容 SHA-256（hex），秒传匹配依据。 */
    private String sha256;
    private String mimeType;
    /** UPLOADED(待处理)/PARSING/CHUNKING/EMBEDDING/INDEXING/READY/STOPPED(已停止)/FAILED */
    private String status;
    private Integer progress;
    private Integer pageCount;
    private String warning;
    private String errorMsg;
    /** 停止处理标记：0 正常，1 用户请求停止 */
    private Integer stopRequested;
    /** 逻辑删除：0=正常 1=已删除。 */
    @TableLogic
    private Integer deleted;
}
