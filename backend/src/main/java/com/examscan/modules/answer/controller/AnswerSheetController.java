package com.examscan.modules.answer.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examscan.common.R;
import com.examscan.modules.answer.dto.AnswerSetRequest;
import com.examscan.modules.answer.entity.AnswerSheet;
import com.examscan.modules.answer.service.AnswerSheetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/answer-sheet")
@RequiredArgsConstructor
public class AnswerSheetController {

    private final AnswerSheetService service;

    /** 依据试卷生成答题卡 */
    @PostMapping("/generate")
    public R<AnswerSheet> generate(@RequestParam Long paperId,
                                   @RequestParam(required = false) String title) {
        return R.ok(service.generate(paperId, title));
    }

    /** 按最新规则重新排版已有答题卡（保留标题与标准答案） */
    @PostMapping("/{id}/regenerate")
    public R<AnswerSheet> regenerate(@PathVariable Long id) {
        return R.ok(service.regenerate(id));
    }

    @GetMapping("/list")
    public R<Page<AnswerSheet>> list(@RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "10") int size) {
        return R.ok(service.list(page, size));
    }

    @GetMapping("/{id}")
    public R<AnswerSheet> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    /** 预览图（PNG），可直接打印 */
    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long id,
                                        @RequestParam(defaultValue = "1") int scale) {
        AnswerSheet s = service.get(id);
        BufferedImage img = service.renderPreview(s, Math.max(1, scale));
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            String fn = URLEncoder.encode(s.getTitle() + ".png", StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + fn)
                    .contentType(MediaType.IMAGE_PNG)
                    .body(out.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 按 A4 分页的多页 PDF，每页答题卡对应一张 A4，直接打印不串页 */
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        AnswerSheet s = service.get(id);
        byte[] data = service.renderPdf(s);
        try {
            String fn = URLEncoder.encode(s.getTitle() + ".pdf", StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fn)
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 单页 PNG（0 起），供浏览器按 A4 逐页打印 */
    @GetMapping("/{id}/page/{pageIndex}/image")
    public ResponseEntity<byte[]> pageImage(@PathVariable Long id,
                                            @PathVariable int pageIndex,
                                            @RequestParam(defaultValue = "1") int scale) {
        AnswerSheet s = service.get(id);
        BufferedImage img = service.renderPage(s, pageIndex, Math.max(1, scale));
        if (img == null) {
            return ResponseEntity.notFound().build();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(out.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 答案清单：按卷内顺序返回题目与题库当前答案 */
    @GetMapping("/{id}/answers")
    public R<Map<String, Object>> answers(@PathVariable Long id) {
        return R.ok(service.answerList(id));
    }

    /** 快速保存标准答案：写入题库，并同步答题卡布局快照 */
    @PostMapping("/{id}/answers")
    public R<Map<String, Object>> saveAnswers(@PathVariable Long id,
                                              @RequestBody AnswerSetRequest req) {
        return R.ok(service.saveAnswers(id, req));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }
}
