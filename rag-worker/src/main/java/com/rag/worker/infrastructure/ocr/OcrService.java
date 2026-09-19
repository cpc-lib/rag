package com.rag.worker.infrastructure.ocr;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.File;

/**
 * OCR（Tess4J）：tessdata 未配置或识别失败时由调用方降级。
 */
@Slf4j
@Component
public class OcrService {

    private final String tessdata;
    private final String langs;
    private volatile Tesseract tesseract;

    public OcrService(@Value("${rag.ocr.tessdata:}") String tessdata,
                      @Value("${rag.ocr.langs:chi_sim+eng}") String langs) {
        this.tessdata = tessdata;
        this.langs = langs;
    }

    public boolean available() {
        return tessdata != null && !tessdata.isBlank() && new File(tessdata).isDirectory();
    }

    public String ocr(BufferedImage image) throws Exception {
        Tesseract t = instance();
        if (t == null) {
            throw new IllegalStateException("OCR 未配置（rag.ocr.tessdata）");
        }
        synchronized (t) {
            t.setLanguage(langs);
            return t.doOCR(image);
        }
    }

    private Tesseract instance() {
        if (!available()) {
            return null;
        }
        if (tesseract == null) {
            synchronized (this) {
                if (tesseract == null) {
                    tesseract = new Tesseract();
                    tesseract.setDatapath(tessdata);
                }
            }
        }
        return tesseract;
    }
}
