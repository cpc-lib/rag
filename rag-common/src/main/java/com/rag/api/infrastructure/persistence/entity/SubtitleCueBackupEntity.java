package com.rag.api.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 字幕详情备份行：与 subtitle_cue 同步，字幕记录删除后保留，供文件库归档继续编辑。 */
@Data
@TableName("subtitle_cue_backup")
public class SubtitleCueBackupEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private Long subtitleId;
    /** 序号，1 起。 */
    private Integer seq;
    private String startTime;
    private String endTime;
    /** 原文文本。 */
    private String content;
    /** 翻译后文本，null 表示未翻译。 */
    private String translatedText;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 逻辑删除：0=正常 1=已删除。 */
    private Integer deleted;
}
