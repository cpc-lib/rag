package com.rag.worker.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 文件库条目（Worker 侧仅读取源对象键、回写转码产物字段）。 */
@Data
@TableName("library_file")
public class LibraryFileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private String fileName;
    private String objectKey;
    /** 源文件字节数（下载进度日志展示用）。 */
    private Long fileSize;
    /** 转码产物对象键（HLS 时为 master.m3u8 的 key）。 */
    private String playbackKey;
    /** 转码状态：NONE/PROCESSING/READY/FAILED。 */
    private String playbackStatus;
    /** 转码进度 0-100（PROCESSING 时有效）。 */
    private Integer playbackProgress;
}
