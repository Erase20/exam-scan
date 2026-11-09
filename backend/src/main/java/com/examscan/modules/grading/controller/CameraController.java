package com.examscan.modules.grading.controller;

import com.examscan.common.R;
import com.examscan.modules.grading.service.PerspectiveCorrector;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手机拍照取景辅助：按答题卡四角定位标记自动透视校正/裁切。
 * 复用阅卷时的 PerspectiveCorrector，保证相机端与判分端坐标一致。
 */
@RestController
@RequestMapping("/api/camera")
public class CameraController {

    private static final int PAGE_W = 2480;
    private static final int PAGE_H = 3508;

    /**
     * 自动裁切：找到四个定位点则输出拉正后的标准 A4 页面（image/jpeg）；
     * 找不到返回 422，前端据此提示重拍，绝不静默返回未裁切的原图。
     */
    @PostMapping("/auto-crop")
    public ResponseEntity<?> autoCrop(@RequestParam("file") MultipartFile file) {
        BufferedImage img = read(file);
        if (img == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "无法读取图片"));
        }
        BufferedImage out;
        try {
            out = PerspectiveCorrector.autoCorrect(img, PAGE_W, PAGE_H);
        } catch (Exception e) {
            return ResponseEntity.status(422).body(Map.of("message", "定位点校正失败: " + e.getMessage()));
        }
        if (out == null) {
            return ResponseEntity.status(422).body(Map.of("message", "未识别到四角定位点"));
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(out, "jpg", bos);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .header("X-Auto-Crop", "markers")
                    .body(bos.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("message", "图片编码失败"));
        }
    }

    /** 实时取景：只检测四角定位点，用于相机界面显示「已锁定点位」状态 */
    @PostMapping("/detect")
    public R<Map<String, Object>> detect(@RequestParam("file") MultipartFile file) {
        BufferedImage img = read(file);
        if (img == null) {
            return R.ok(Map.of("found", false, "points", List.of()));
        }
        double[][] pts;
        try {
            pts = PerspectiveCorrector.detectMarkers(img, PAGE_W, PAGE_H);
        } catch (Exception e) {
            pts = null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (pts == null) {
            out.put("found", false);
            out.put("points", List.of());
            return R.ok(out);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (double[] p : pts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", p[0]);
            m.put("y", p[1]);
            list.add(m);
        }
        out.put("found", true);
        out.put("points", list);
        out.put("width", img.getWidth());
        out.put("height", img.getHeight());
        return R.ok(out);
    }

    private BufferedImage read(MultipartFile file) {
        try {
            return ImageIO.read(file.getInputStream());
        } catch (Exception e) {
            return null;
        }
    }
}
