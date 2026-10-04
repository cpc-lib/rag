package com.rag.api.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.api.common.BizException;
import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.image.ZImageClient;
import com.rag.api.infrastructure.persistence.entity.GeneratedImageEntity;
import com.rag.api.infrastructure.persistence.entity.ModelEntity;
import com.rag.api.infrastructure.persistence.mapper.GeneratedImageMapper;
import com.rag.api.infrastructure.storage.MinioStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文生图编排：取租户模型配置（留空回退官方地址/LLM Key/z-image-turbo）
 * → 同步生成 → 下载图片持久化到 MinIO → 落 generated_image 历史。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageGenerationService {

    /** 下载视图：鉴权后从 MinIO 读取原图字节。 */
    public record Download(String fileName, String contentType, byte[] bytes) {
    }

    /** 历史视图：url 为 MinIO 预签名地址。 */
    public record ImageView(long id, String prompt, String model, String size, Long seed,
                            String url, LocalDateTime createdAt, Long fileSize) {
    }

    private static final String DEFAULT_BASE = "https://dashscope.aliyuncs.com";
    private static final String DEFAULT_MODEL = "z-image-turbo";
    private static final String DEFAULT_SIZE = "1024*1536";
    private static final Pattern SIZE_PATTERN = Pattern.compile("^(\\d{3,4})\\*(\\d{3,4})$");

    private final ModelService modelService;
    private final GeneratedImageMapper imageMapper;
    private final ZImageClient zImageClient;
    private final MinioStorage minio;
    private final UserManageService userManageService;
    private final FileLibraryService fileLibraryService;

    public ImageView generate(String prompt, String size, Long seed) {
        TenantContext.Session s = TenantContext.require();
        userManageService.requireMenu("image-studio");
        ModelEntity m = modelService.requireEnabled(s.tenantId(), ModelService.IMAGE);

        String base = blank(m.getBaseUrl()) ? DEFAULT_BASE : m.getBaseUrl().trim();
        String apiKey = m.getApiKey();
        if (blank(apiKey)) {
            throw BizException.badRequest("启用的文生图模型未配置 API Key");
        }
        String model = blank(m.getModel()) ? DEFAULT_MODEL : m.getModel().trim();
        String actualSize = validateSize(size);

        String remoteUrl = zImageClient.generate(base, apiKey, model,
                prompt.strip(), actualSize, seed);
        byte[] bytes = zImageClient.download(remoteUrl);

        String fileName = UUID.randomUUID() + ".png";
        String objectKey = s.tenantId() + "/generated/" + s.userId() + "/" + fileName;
        minio.upload(objectKey, new ByteArrayInputStream(bytes), bytes.length, "image/png");

        GeneratedImageEntity e = new GeneratedImageEntity();
        e.setTenantId(s.tenantId());
        e.setUserId(s.userId());
        e.setPrompt(prompt.strip());
        e.setModel(model);
        e.setSize(actualSize);
        e.setSeed(seed);
        e.setObjectKey(objectKey);
        e.setFileName(fileName);
        e.setFileSize((long) bytes.length);
        e.setStatus("SUCCESS");
        imageMapper.insert(e);

        // 同步登记到文件库（复用同一 MinIO 对象，不重复存储）；归档失败不阻断生成结果
        try {
            fileLibraryService.archiveImage(s.tenantId(), s.userId(), e.getId(),
                    "ai-image-" + e.getId() + ".png", objectKey, "image/png", (long) bytes.length);
        } catch (Exception ex) {
            log.warn("图片归档文件库失败（不影响生成结果）: {}", ex.getMessage());
        }
        return toView(e);
    }

    /** 个人生成历史分页（仅本人可见）。 */
    public Page<ImageView> page(long current, long size) {
        TenantContext.Session s = TenantContext.require();
        Page<GeneratedImageEntity> p = new Page<>(current, size);
        imageMapper.selectPage(p, new QueryWrapper<GeneratedImageEntity>()
                .eq("tenant_id", s.tenantId())
                .eq("user_id", s.userId())
                .orderByDesc("id"));
        Page<ImageView> views = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        views.setRecords(p.getRecords().stream().map(e -> {
            if (e.getFileSize() == null) {
                backfillSize(e);
            }
            return toView(e);
        }).toList());
        return views;
    }

    /** 历史行无大小：查 MinIO 后回写（失败静默，不阻塞列表）。 */
    private void backfillSize(GeneratedImageEntity e) {
        try {
            long size = minio.statSize(e.getObjectKey());
            e.setFileSize(size);
            imageMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<GeneratedImageEntity>()
                    .eq("id", e.getId()).set("file_size", size));
        } catch (Exception ex) {
            log.warn("作品大小回填失败 id={}: {}", e.getId(), ex.getMessage());
        }
    }

    /** 下载本人作品：校验归属后从 MinIO 读取字节。 */
    public Download download(long id) {
        TenantContext.Session s = TenantContext.require();
        GeneratedImageEntity e = imageMapper.selectById(id);
        if (e == null || !s.tenantId().equals(e.getTenantId()) || e.getUserId() != s.userId()) {
            throw BizException.notFound("图片不存在");
        }
        try (java.io.InputStream in = minio.download(e.getObjectKey())) {
            return new Download(e.getFileName(), "image/png", in.readAllBytes());
        } catch (java.io.IOException ex) {
            throw new BizException(com.rag.api.common.ErrorCode.INTERNAL, "读取图片失败：" + ex.getMessage());
        }
    }

    private String validateSize(String size) {
        if (blank(size)) {
            return DEFAULT_SIZE;
        }
        Matcher m = SIZE_PATTERN.matcher(size.trim());
        if (!m.matches()) {
            throw BizException.badRequest("size 格式应为 宽*高，如 1024*1024");
        }
        long w = Long.parseLong(m.group(1));
        long h = Long.parseLong(m.group(2));
        long pixels = w * h;
        if (pixels < 512L * 512 || pixels > 2048L * 2048) {
            throw BizException.badRequest("总像素需在 512*512 ~ 2048*2048 之间");
        }
        return w + "*" + h;
    }

    private ImageView toView(GeneratedImageEntity e) {
        return new ImageView(e.getId(), e.getPrompt(), e.getModel(), e.getSize(),
                e.getSeed(), minio.presignUrl(e.getObjectKey()), e.getCreatedAt(), e.getFileSize());
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
