package com.rag.api.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 字幕详情行：一条字幕记录被解析出的单条字幕（原文 + 译文分离）。 */
@Data
@TableName("subtitle_cue")
public class SubtitleCueEntity {

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
}
