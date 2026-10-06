package com.rag.api.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 视频播放进度记录：按租户+用户+文件维度保存最新播放位置，用于续播与播放记录。 */
@Data
@TableName("video_playback_history")
public class VideoPlaybackHistoryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private Long userId;
    private Long fileId;
    /** 文件名快照（文件被删除后仍可在播放记录中展示）。 */
    private String fileName;
    /** 最新播放位置（毫秒）。 */
    private Long positionMs;
    /** 视频总时长（毫秒）。 */
    private Long durationMs;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    /** 逻辑删除：0=正常 1=已删除。 */
    private Integer deleted;
}
