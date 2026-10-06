package com.rag.api.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 文件库条目：MinIO 对象的元数据（字幕保存等操作归档）。 */
@Data
@TableName("library_file")
public class LibraryFileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private Long userId;
    /** 来源字幕记录ID，为空表示非字幕业务。 */
    private Long subtitleId;
    /** 来源AI图片作品ID（generated_image），为空表示非图片业务。 */
    private Long imageId;
    /** 来源知识库文档ID（document），为空表示非知识库文档业务。 */
    private Long documentId;
    /** 归档来源：SUBTITLE_UPLOAD=字幕上传转存 / SUBTITLE_SAVE=翻译保存归档 / DIRECT=文件库直接上传。 */
    private String archiveSource;
    private String fileName;
    private String objectKey;
    /** 转码后的可播放对象键（avi/ts 等转 mp4 后的 MinIO key）。 */
    private String playbackKey;
    /** 转码状态：NONE/PROCESSING/READY/FAILED。 */
    private String playbackStatus;
    /** 转码进度 0-100（PROCESSING 时有效）。 */
    private Integer playbackProgress;
    /** 视频宽度（像素，转码探测写入）。 */
    private Integer videoWidth;
    /** 视频高度（像素，转码探测写入）。 */
    private Integer videoHeight;
    private String contentType;
    private Long fileSize;
    /** 文件内容 SHA-256（hex），秒传匹配依据。 */
    private String sha256;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    /** 逻辑删除：0=正常 1=已删除。 */
    private Integer deleted;
}
