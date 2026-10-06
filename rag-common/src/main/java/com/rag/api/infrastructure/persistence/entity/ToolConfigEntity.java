package com.rag.api.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("tool_config")
public class ToolConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private Boolean weatherEnabled;
    private Boolean tavilyEnabled;
    private String tavilyApiKey;
    /** 逻辑删除：0=正常 1=已删除。 */
    private Integer deleted;
}
