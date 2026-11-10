package com.examscan.modules.paper.controller;

import com.examscan.common.R;
import com.examscan.modules.paper.dto.CustomPaperRequest;
import com.examscan.modules.paper.dto.PaperConfig;
import com.examscan.modules.paper.entity.Paper;
import com.examscan.modules.paper.service.PaperExportService;
import com.examscan.modules.paper.service.PaperService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/paper")
@RequiredArgsConstructor
public class PaperController {

    private final PaperService paperService;
    private final PaperExportService exportService;

    /** 自动组卷 */
    @PostMapping("/auto-build")
    public R<Map<String, Object>> autoBuild(@RequestBody PaperConfig cfg) {
        return R.ok(paperService.autoBuild(cfg));
    }

    /** 自定义组卷：用户逐题勾选并设置分值 */
    @PostMapping("/custom-build")
    public R<Map<String, Object>> customBuild(@RequestBody CustomPaperRequest req) {
        return R.ok(paperService.customBuild(req));
    }

    @GetMapping("/list")
    public R<List<Paper>> list() {
        return R.ok(paperService.list());
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(paperService.detail(id));
    }

    /** 修改试卷名称（导出 Word 的文件名会随之更新） */
    @PostMapping("/{id}/title")
    public R<Paper> rename(@PathVariable Long id, @RequestParam String title) {
        return R.ok(paperService.renamePaper(id, title));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        paperService.deletePaper(id);
        return R.ok();
    }

    /** 批量删除试卷（连同试卷明细） */
    @PostMapping("/batch-delete")
    public R<Integer> batchDelete(@RequestBody List<Long> ids) {
        int n = 0;
        if (ids != null) {
            for (Long id : ids) {
                paperService.deletePaper(id);
                n++;
            }
        }
        return R.ok(n);
    }

    /** 统一所有试卷及答题卡的大题顺序（填空→判断→选择→解答→实验探究→作图→计算→其他） */
    @PostMapping("/sections/reorder")
    public R<Map<String, Object>> reorderAllSections() {
        return R.ok(paperService.reorderAllSections());
    }

    /** 调整单题分值 */
    @PostMapping("/item/{itemId}/score")
    public R<BigDecimal> updateScore(@PathVariable Long itemId,
                                     @RequestParam BigDecimal score) {
        paperService.updateItemScore(itemId, score);
        return R.ok(score);
    }

    @DeleteMapping("/item/{itemId}")
    public R<Void> removeItem(@PathVariable Long itemId) {
        paperService.removeItem(itemId);
        return R.ok();
    }

    /** 题目上下换位 */
    @PostMapping("/item/{itemId}/move")
    public R<Void> moveItem(@PathVariable Long itemId,
                            @RequestParam String direction) {
        paperService.moveItem(itemId, direction);
        return R.ok();
    }

    /** 向试卷追加题目 */
    @PostMapping("/{paperId}/item")
    public R<Void> addItem(@PathVariable Long paperId,
                           @RequestParam Long questionId,
                           @RequestParam(required = false) String qType,
                           @RequestParam(required = false) BigDecimal score) {
        paperService.addItem(paperId, questionId, qType, score);
        return R.ok();
    }

    /** 用题库里的另一道题替换试卷中已有的某题（保留原位置与分值） */
    @PostMapping("/item/{itemId}/replace")
    public R<Void> replaceItem(@PathVariable Long itemId,
                               @RequestParam Long questionId,
                               @RequestParam(required = false) BigDecimal score) {
        paperService.replaceItem(itemId, questionId, score);
        return R.ok();
    }

    /**
     * 导出 Word。
     *
     * @param answerMode 答案呈现：none（不含答案）/ inline（答案填在相应位置：填空逐空填入空位）/ separate（答案集中排在末页）
     * @param withAnswer 兼容旧参数：true 等价于 answerMode=separate
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<?> export(@PathVariable Long id,
                                    @RequestParam(required = false) String answerMode,
                                    @RequestParam(defaultValue = "false") boolean withAnswer) {
        String mode = (answerMode == null || answerMode.isBlank())
                ? (withAnswer ? "separate" : "none") : answerMode;
        String tag = switch (mode) {
            case "inline" -> "_含答案";
            case "separate" -> "_含答案(答案页)";
            default -> "";
        };
        try {
            Map<String, Object> d = paperService.detail(id);
            Paper paper = (Paper) d.get("paper");
            byte[] bytes = exportService.exportDocx(id, mode);
            String fn = URLEncoder.encode(paper.getTitle() + tag + ".docx",
                    StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fn)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.status(500).contentType(MediaType.APPLICATION_JSON)
                    .body(R.fail("导出失败: " + e.getMessage()));
        }
    }
}
