package com.rag.api.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_menu")
public class SysMenuEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;
    private String name;
    private String path;
    private String icon;
    private Boolean platformVisible;
    private Boolean adminVisible;
    private Boolean endUser;
    private Integer sort;
    private Boolean status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
