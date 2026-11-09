package com.examscan.modules.file.controller;

import com.examscan.common.BizException;
import com.examscan.common.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 通用附件上传：目前用于题目配图（单题编辑时替换/新增图片）。
 * 文件落到 storage/attachment/yyyyMM/，通过 /files/** 直接访问。
 */
@Slf4j
@RestController
@RequestMapping("/api/file")
public class FileController {

    private static final Set<String> IMAGE_EXT = Set.of(".png", ".jpg", ".jpeg", ".gif", ".bmp");

    @Value("${exam-scan.storage.root:./data/storage}")
    private String storageRoot;

    @PostMapping("/upload")
    public R<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的图片");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extOf(original);
        if (!IMAGE_EXT.contains(ext)) {
            throw new BizException("仅支持 png / jpg / jpeg / gif / bmp 图片");
        }
        try {
            byte[] bytes = file.getBytes();
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(bytes));
            if (bi == null || bi.getWidth() <= 0 || bi.getHeight() <= 0) {
                throw new BizException("文件不是有效图片");
            }
            String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
            Path dir = Paths.get(storageRoot).toAbsolutePath().normalize()
                    .resolve("attachment").resolve(month);
            Files.createDirectories(dir);
            String name = UUID.randomUUID().toString().replace("-", "") + ext;
            Files.write(dir.resolve(name), bytes);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", "/files/attachment/" + month + "/" + name);
            m.put("name", name);
            m.put("width", bi.getWidth());
            m.put("height", bi.getHeight());
            m.put("size", bytes.length);
            return R.ok(m);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("图片上传失败", e);
            throw new BizException("图片上传失败：" + e.getMessage());
        }
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i).toLowerCase(Locale.ROOT);
    }
}
