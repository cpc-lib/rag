package com.rag.api.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rag.api.common.BizException;
import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.persistence.entity.LibraryFileEntity;
import com.rag.api.infrastructure.persistence.mapper.LibraryFileMapper;
import com.rag.api.infrastructure.storage.MinioStorage;
import com.rag.api.interfaces.dto.Dtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文件库：字幕保存等操作产出的文件归档到 MinIO，元数据落 library_file。
 * 每次保存新增一条归档（版本化），保留历史版本不覆盖。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileLibraryService {

    /** 业务类型：字幕文件（关联字幕记录）。 */
    public static final String BIZ_SUBTITLE = "SUBTITLE";
    /** 业务类型：AI 图片文件（关联图片作品）。 */
    public static final String BIZ_IMAGE = "IMAGE";
    /** 业务类型：知识库文档（关联 document 记录）。 */
    public static final String BIZ_DOCUMENT = "DOCUMENT";
    /** 业务类型：其他文件（无业务关联，只读预览）。 */
    public static final String BIZ_OTHER = "OTHER";

    /** 归档来源：字幕上传后自动转存的原始文件归档（文件库不可删除）。 */
    public static final String SRC_SUBTITLE_UPLOAD = "SUBTITLE_UPLOAD";
    /** 归档来源：字幕翻译/编辑后手动保存的归档（文件库可删除）。 */
    public static final String SRC_SUBTITLE_SAVE = "SUBTITLE_SAVE";
    /** 归档来源：文件库直接上传（文件库可删除）。 */
    public static final String SRC_DIRECT = "DIRECT";

    private final LibraryFileMapper libraryFileMapper;
    private final MinioStorage minioStorage;
    private final UserManageService userManageService;
    private final MediaTranscodeService mediaTranscodeService;

    /**
     * 字幕保存时归档：每次保存新增一条记录，保留历史版本；MinIO 故障由调用方降级处理。
     */
    public void saveFromSubtitle(String tenantId, long userId, long subtitleId, String fileName, String srt) {
        archive(tenantId, userId, subtitleId, fileName, "application/x-subrip", SRC_SUBTITLE_SAVE,
                srt.getBytes(StandardCharsets.UTF_8));
    }

    /** 通用归档：来源文件（原始上传、保存产物等）存 MinIO + 元数据落库，每次新增一条记录，保留历史版本。 */
    public void archive(String tenantId, long userId, long subtitleId, String fileName, String contentType,
                        String archiveSource, byte[] bytes) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot) : "";
        String objectKey = tenantId + "/library/" + subtitleId + "/" + System.currentTimeMillis() + ext;
        minioStorage.upload(objectKey, new java.io.ByteArrayInputStream(bytes), bytes.length, contentType);

        LibraryFileEntity e = new LibraryFileEntity();
        e.setTenantId(tenantId);
        e.setUserId(userId);
        e.setSubtitleId(subtitleId);
        e.setArchiveSource(archiveSource);
        e.setFileName(fileName);
        e.setObjectKey(objectKey);
        e.setContentType(contentType);
        e.setFileSize((long) bytes.length);
        libraryFileMapper.insert(e);
    }

    /**
     * AI 图片归档：登记已存在的 MinIO 对象到文件库（与图片作品共用同一对象，不重复上传占用存储）。
     * 内部调用，不校验文件库菜单（仅有图片功能的用户也可能产出）。
     */
    public void archiveImage(String tenantId, long userId, long imageId, String fileName,
                             String objectKey, String contentType, long fileSize) {
        LibraryFileEntity e = new LibraryFileEntity();
        e.setTenantId(tenantId);
        e.setUserId(userId);
        e.setImageId(imageId);
        e.setFileName(fileName);
        e.setObjectKey(objectKey);
        e.setContentType(contentType);
        e.setFileSize(fileSize);
        libraryFileMapper.insert(e);
    }

    /**
     * 知识库文档归档：登记已存在的 MinIO 对象到文件库（与文档共用同一对象，不重复上传占用存储）。
     * 内部调用，不校验文件库菜单（仅有知识库功能的用户也可能产出）。
     */
    public void archiveDocument(String tenantId, long userId, long documentId, String fileName,
                                String objectKey, String contentType, long fileSize) {
        LibraryFileEntity e = new LibraryFileEntity();
        e.setTenantId(tenantId);
        e.setUserId(userId);
        e.setDocumentId(documentId);
        e.setFileName(fileName);
        e.setObjectKey(objectKey);
        e.setContentType(contentType);
        e.setFileSize(fileSize);
        libraryFileMapper.insert(e);
    }

    /** 文档删除时级联清理文件库条目（MinIO 对象由文档侧删除，条目残留会成为死数据）。 */
    public void removeByDocument(long documentId) {
        libraryFileMapper.delete(new QueryWrapper<LibraryFileEntity>().eq("document_id", documentId));
    }

    /** 文件库直接上传：任意文件存 MinIO + 元数据落库（bizType=OTHER，可删除）。 */
    public Dtos.LibraryFileView uploadDirect(MultipartFile file) {
        userManageService.requireMenu("library");
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("上传文件为空");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw BizException.badRequest("文件名不能为空");
        }
        TenantContext.Session s = TenantContext.require();
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot) : "";
        String objectKey = s.tenantId() + "/library/direct/" + System.currentTimeMillis() + ext;
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        try (InputStream in = file.getInputStream()) {
            minioStorage.upload(objectKey, in, file.getSize(), contentType);
        } catch (Exception ex) {
            throw new BizException(com.rag.api.common.ErrorCode.UPSTREAM, "上传文件失败: " + ex.getMessage());
        }

        LibraryFileEntity e = new LibraryFileEntity();
        e.setTenantId(s.tenantId());
        e.setUserId(s.userId());
        e.setArchiveSource(SRC_DIRECT);
        e.setFileName(fileName);
        e.setObjectKey(objectKey);
        e.setContentType(contentType);
        e.setFileSize(file.getSize());
        libraryFileMapper.insert(e);
        log.info("文件库直接上传 file={} size={}", fileName, file.getSize());
        return toView(e);
    }

    /** 分片上传完成后登记文件库条目（对象已在 MinIO 合并完成，来源=直接上传，可删除）。 */
    public Dtos.LibraryFileView completeDirectUpload(String tenantId, long userId, String fileName,
                                                     String objectKey, String contentType, long fileSize) {
        LibraryFileEntity e = new LibraryFileEntity();
        e.setTenantId(tenantId);
        e.setUserId(userId);
        e.setArchiveSource(SRC_DIRECT);
        e.setFileName(fileName);
        e.setObjectKey(objectKey);
        e.setContentType(contentType);
        e.setFileSize(fileSize);
        libraryFileMapper.insert(e);
        log.info("分片上传完成归档 file={} size={}", fileName, fileSize);
        return toView(e);
    }

    /**
     * 删除文件库条目：仅允许删除"字幕翻译保存归档"与"文件库直接上传"的文件；
     * 知识库文档 / AI 图片归档（MinIO 对象与业务共享）及字幕原始转存文件不可删除。
     */
    public void delete(long id) {
        userManageService.requireMenu("library");
        LibraryFileEntity e = requireOwned(id);
        if (e.getDocumentId() != null) {
            throw BizException.badRequest("知识库文档归档不可在此删除，请在知识库中删除文档");
        }
        if (e.getImageId() != null) {
            throw BizException.badRequest("AI 图片归档不可在此删除，请在图片作品中删除");
        }
        if (e.getSubtitleId() != null && !SRC_SUBTITLE_SAVE.equals(e.getArchiveSource())) {
            throw BizException.badRequest("字幕原始转存文件不可删除");
        }
        // 可删除来源的 MinIO 对象均为文件库专有（保存归档/直接上传），直接清理
        try {
            minioStorage.deleteObject(e.getObjectKey());
            // 旧版 mp4 转码产物
            if (e.getPlaybackKey() != null && !e.getPlaybackKey().isBlank()
                    && !e.getPlaybackKey().endsWith(".m3u8")) {
                minioStorage.deleteObject(e.getPlaybackKey());
            }
            // HLS 产物为目录（{objectKey}.hls/），按前缀整体清理
            minioStorage.deletePrefix(e.getObjectKey() + ".hls/");
        } catch (Exception ex) {
            log.warn("文件库 MinIO 对象删除失败 id={} err={}", e.getId(), ex.getMessage());
        }
        libraryFileMapper.deleteById(e.getId());
        log.info("文件库条目已删除 id={} file={}", e.getId(), e.getFileName());
    }

    public List<Dtos.LibraryFileView> list(String keyword) {
        userManageService.requireMenu("library");
        TenantContext.Session s = TenantContext.require();
        QueryWrapper<LibraryFileEntity> qw = new QueryWrapper<LibraryFileEntity>()
                .eq("tenant_id", s.tenantId())
                .eq("user_id", s.userId());
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            // 转义 LIKE 通配符，避免用户输入的 % _ 被当作通配
            String escaped = kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            qw.like("file_name", escaped);
        }
        qw.orderByDesc("updated_at");
        return libraryFileMapper.selectList(qw).stream().map(this::toView).toList();
    }

    public Dtos.LibraryFileView get(long id) {
        userManageService.requireMenu("library");
        return toView(requireOwned(id));
    }

    /** 媒体流：鉴权+归属校验后返回实体（objectKey/fileSize 供控制器 Range 流式输出）。 */
    public LibraryFileEntity streamInfo(long id) {
        userManageService.requireMenu("library");
        return requireOwned(id);
    }

    /** 查询播放状态（纯查询）：NONE/PROCESSING/READY/FAILED + 转码进度。 */
    public Dtos.PlaybackResp playback(long id) {
        userManageService.requireMenu("library");
        return mediaTranscodeService.playbackStatus(requireOwned(id));
    }

    /** 开始/重新转码（幂等）：视频且非转码中时投递 MQ。 */
    public Dtos.PlaybackResp startTranscode(long id) {
        userManageService.requireMenu("library");
        return mediaTranscodeService.startTranscode(requireOwned(id));
    }

    /** 停止转码：状态回到 NONE，Worker 强杀 ffmpeg。 */
    public Dtos.PlaybackResp stopTranscode(long id) {
        userManageService.requireMenu("library");
        return mediaTranscodeService.stopTranscode(requireOwned(id));
    }

    /** 查看文本内容（SRT/VTT 等文本文件）。 */
    public String content(long id) {
        userManageService.requireMenu("library");
        LibraryFileEntity e = requireOwned(id);
        try (InputStream is = minioStorage.download(e.getObjectKey())) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new BizException(com.rag.api.common.ErrorCode.UPSTREAM, "读取文件内容失败: " + ex.getMessage());
        }
    }

    public byte[] download(long id) {
        userManageService.requireMenu("library");
        LibraryFileEntity e = requireOwned(id);
        try (InputStream is = minioStorage.download(e.getObjectKey())) {
            return is.readAllBytes();
        } catch (Exception ex) {
            throw new BizException(com.rag.api.common.ErrorCode.UPSTREAM, "读取文件失败: " + ex.getMessage());
        }
    }

    /**
     * 文件库内直接编辑归档文件：以条目覆盖写回同一 MinIO 对象（objectKey 不变，不新增版本、
     * 不触碰字幕主数据）。译文非空取译文，否则取原文（与下载 SRT 的取值规则一致）；
     * .vtt 文件保持 VTT 格式输出，其余按 SRT。
     */
    public Dtos.LibraryFileView saveCues(long id, List<Dtos.SubtitleCue> cues) {
        userManageService.requireMenu("library");
        LibraryFileEntity e = requireOwned(id);
        if (cues == null || cues.isEmpty()) {
            throw BizException.badRequest("字幕内容为空，无法保存");
        }
        List<SubtitleCodec.Cue> out = cues.stream()
                .map(c -> {
                    String translated = c.translated();
                    String text = translated != null && !translated.isBlank()
                            ? translated : (c.text() == null ? "" : c.text());
                    return new SubtitleCodec.Cue(c.start(), c.end(), text);
                })
                .toList();
        String content = "vtt".equals(SubtitleCodec.extOf(e.getFileName()))
                ? SubtitleCodec.toVtt(out) : SubtitleCodec.toSrt(out);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try {
            minioStorage.upload(e.getObjectKey(), new java.io.ByteArrayInputStream(bytes),
                    bytes.length, e.getContentType());
        } catch (Exception ex) {
            throw new BizException(com.rag.api.common.ErrorCode.UPSTREAM, "写入文件失败: " + ex.getMessage());
        }
        e.setFileSize((long) bytes.length);
        libraryFileMapper.updateById(e);
        return toView(e);
    }

    private LibraryFileEntity requireOwned(long id) {
        LibraryFileEntity e = libraryFileMapper.selectById(id);
        TenantContext.Session s = TenantContext.require();
        if (e == null || !s.tenantId().equals(e.getTenantId())
                || e.getUserId() == null || s.userId() != e.getUserId()) {
            throw BizException.notFound("文件不存在");
        }
        return e;
    }

    private Dtos.LibraryFileView toView(LibraryFileEntity e) {
        // 业务类型按数据来源（业务关联外键）判定，不依赖文件扩展名
        String bizType;
        if (e.getSubtitleId() != null) {
            bizType = BIZ_SUBTITLE;
        } else if (e.getImageId() != null) {
            bizType = BIZ_IMAGE;
        } else if (e.getDocumentId() != null) {
            bizType = BIZ_DOCUMENT;
        } else {
            bizType = BIZ_OTHER;
        }
        // 删除规则：知识库文档/AI 图片归档不可删；字幕仅"翻译保存归档"可删；直接上传可删
        boolean deletable = e.getDocumentId() == null && e.getImageId() == null
                && (e.getSubtitleId() == null || SRC_SUBTITLE_SAVE.equals(e.getArchiveSource()));
        return new Dtos.LibraryFileView(e.getId(), e.getFileName(), e.getContentType(),
                e.getFileSize() == null ? 0 : e.getFileSize(), e.getSubtitleId(), bizType,
                deletable, e.getPlaybackStatus(), e.getPlaybackProgress(), e.getUpdatedAt());
    }
}
