package com.rag.worker.pipeline.image;

import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.api.infrastructure.persistence.entity.ModelEntity;
import com.rag.worker.infrastructure.llm.VisionClient;
import com.rag.worker.infrastructure.ocr.OcrService;
import com.rag.worker.infrastructure.storage.ObjectStorage;
import com.rag.worker.pipeline.ParseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 内嵌图片资产化：OCR + 视觉描述 → 上传对象存储 → 输出可直接落库的 IMAGE 切片素材。
 * 两种文本都没拿到时跳过该图并记 warning，不阻断流水线。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageAssetService {

    /** 已描述并上传的图片；objectKey 为对象存储 Key，ocr/vision 至少一个非空。 */
    public record ImageAsset(int page, String objectKey, String ocr, String vision) {
    }

    public record Result(List<ImageAsset> assets, List<String> warnings) {
    }

    private final ObjectStorage minio;
    private final OcrService ocrService;
    private final VisionClient visionClient;

    public Result process(DocumentEntity doc, List<ParseService.RawImage> images, ModelEntity vision) {
        List<ImageAsset> assets = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (images == null || images.isEmpty()) {
            return new Result(assets, warnings);
        }
        boolean visionReady = vision != null
                && visionClient.isConfigured(vision.getBaseUrl(), vision.getModel());
        int skippedNoText = 0;
        int failedUpload = 0;
        for (ParseService.RawImage image : images) {
            // 整页截图的文本层已单独抽取，不再 OCR（避免重复入索引）
            String ocr = image.pageSnapshot() ? null : ocrText(doc, image);
            String visionText = visionReady
                    ? visionClient.describe(vision.getBaseUrl(), vision.getApiKey(), vision.getModel(),
                    image.png(), "image/png")
                    : null;
            if (isBlank(ocr) && isBlank(visionText)) {
                skippedNoText++;
                continue;
            }
            String objectKey = doc.getTenantId() + "/" + doc.getKbId() + "/derived/" + doc.getId()
                    + "/images/p" + (image.page() + 1) + "-" + image.hash() + ".png";
            try {
                minio.upload(objectKey, new ByteArrayInputStream(image.png()), image.png().length, "image/png");
            } catch (Exception e) {
                log.warn("PDF 图片上传失败已跳过 doc={} page={} err={}",
                        doc.getId(), image.page() + 1, e.getMessage());
                failedUpload++;
                continue;
            }
            assets.add(new ImageAsset(image.page(), objectKey,
                    isBlank(ocr) ? null : ocr.strip(),
                    isBlank(visionText) ? null : visionText.strip()));
        }
        // 告警按类聚合，避免逐图拼接超出 document.warning 字段长度
        if (skippedNoText > 0) {
            warnings.add(skippedNoText + "张图片未获得OCR/视觉文本已跳过");
        }
        if (failedUpload > 0) {
            warnings.add(failedUpload + "张图片上传失败已跳过");
        }
        return new Result(assets, warnings);
    }

    private String ocrText(DocumentEntity doc, ParseService.RawImage image) {
        if (!ocrService.available()) {
            return null;
        }
        try {
            BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(image.png()));
            return buffered == null ? null : ocrService.ocr(buffered);
        } catch (Exception e) {
            log.warn("PDF 图片 OCR 失败 doc={} page={} err={}", doc.getId(), image.page() + 1, e.getMessage());
            return null;
        }
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
