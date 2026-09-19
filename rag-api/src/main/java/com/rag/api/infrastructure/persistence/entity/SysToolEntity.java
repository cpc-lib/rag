package com.rag.api.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_tool")
public class SysToolEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;
    private String name;
    private String fnName;
    private String description;
    private Boolean requiresKey;
    private Integer sort;
    private Boolean status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
