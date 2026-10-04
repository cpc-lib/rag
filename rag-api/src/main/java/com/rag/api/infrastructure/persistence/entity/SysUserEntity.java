package com.rag.api.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

@Data
@TableName("sys_user")
public class SysUserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private String username;
    private String passwordHash;
    /** 0=平台超级管理员, 1=租户管理员, 2=租户普通用户 */
    private Integer userType;
    /** 1正常 0停用 */
    private Integer status;
    /** 逻辑删除：0=正常 1=已删除。 */
    @TableLogic
    private Integer deleted;
}
