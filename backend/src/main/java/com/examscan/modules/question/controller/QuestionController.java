package com.examscan.modules.question.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examscan.common.BizException;
import com.examscan.common.R;
import com.examscan.modules.question.dto.QuestionQuery;
import com.examscan.modules.question.entity.Question;
import com.examscan.modules.question.mapper.QuestionMapper;
import com.examscan.modules.question.service.QuestionExportService;
import com.examscan.modules.question.service.QuestionSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/question")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionSearchService searchService;
    private final QuestionExportService exportService;
    private final QuestionMapper questionMapper;

    /** 搜题：条件 + 关键词分页 */
    @PostMapping("/search")
    public R<Page<Question>> search(@RequestBody QuestionQuery q) {
        return R.ok(searchService.search(q));
    }

    /** 相似题推荐 */
    @GetMapping("/{id}/similar")
    public R<List<Map<String, Object>>> similar(@PathVariable Long id,
                                                @RequestParam(defaultValue = "5") int topK) {
        return R.ok(searchService.similar(id, topK));
    }

    /** 按文本片段搜相似题（粘贴题干即可） */
    @PostMapping("/similar-by-text")
    public R<List<Map<String, Object>>> similarByText(@RequestBody Map<String, String> body) {
        String text = body.getOrDefault("text", "");
        return R.ok(searchService.similarByText(text, 10));
    }

    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(searchService.stats());
    }

    /** 题库中已设置的课号去重列表（升序），供筛选下拉框使用 */
    @GetMapping("/course-nos")
    public R<List<String>> courseNos() {
        return R.ok(searchService.distinctCourseNos());
    }

    /**
     * 导出题库为 Excel。
     *
     * 列结构与导入模板一致，便于「导出 → 编辑 → 再导入」。
     * 传入 ids 时只导出指定的题目；否则按当前筛选条件导出（最多 10 万道）。
     */
    @PostMapping("/export")
    public ResponseEntity<?> export(@RequestBody(required = false) QuestionQuery q) {
        try {
            QuestionQuery query = q == null ? new QuestionQuery() : q;
            byte[] data = exportService.export(query, query.getIds());
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
            String fn = URLEncoder.encode("题库导出_" + stamp + ".xlsx", StandardCharsets.UTF_8)
                    .replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fn)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.status(500).contentType(MediaType.APPLICATION_JSON)
                    .body(R.fail("导出失败: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public R<Question> get(@PathVariable Long id) {
        return R.ok(questionMapper.selectById(id));
    }

    /**
     * 新增 / 编辑题目。
     *
     * id 为空 = 手动新增一道题（source 记为 MANUAL，补齐各字段默认值）；
     * id 非空 = 校正已有题目（OCR 识别结果或历史数据）。
     */
    @PostMapping
    public R<Question> save(@RequestBody Question q) {
        if (q.getId() == null) {
            if (q.getStem() == null || q.getStem().isBlank()) {
                throw new BizException("题干不能为空");
            }
            q.setSource(q.getSource() == null || q.getSource().isBlank() ? "MANUAL" : q.getSource());
            q.setStatus(q.getStatus() == null || q.getStatus().isBlank() ? "NORMAL" : q.getStatus());
            q.setQType(q.getQType() == null || q.getQType().isBlank() ? "OTHER" : q.getQType());
            q.setDifficulty(q.getDifficulty() == null ? 3 : Math.max(1, Math.min(5, q.getDifficulty())));
            q.setSeq(q.getSeq() == null ? 0 : q.getSeq());
            q.setSectionOrder(q.getSectionOrder() == null ? 0 : q.getSectionOrder());
            q.setHasImage(q.getHasImage() == null ? 0 : q.getHasImage());
            q.setDeleted(0);
            questionMapper.insert(q);
        } else {
            questionMapper.updateById(q);
        }
        return R.ok(q);
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        questionMapper.deleteById(id);
        return R.ok();
    }

    /** 批量删除 */
    @PostMapping("/batch-delete")
    public R<Void> batchDelete(@RequestBody List<Long> ids) {
        if (ids != null && !ids.isEmpty()) {
            questionMapper.deleteByIds(ids);
        }
        return R.ok();
    }

    /** 清空确认页：预览将被删除的数据量 */
    @GetMapping("/clear-preview")
    public R<Map<String, Object>> clearPreview() {
        Map<String, Object> cnt = questionMapper.countBySource();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", num(cnt, "TOTAL", "total"));
        m.put("scanCount", num(cnt, "SCANCOUNT", "scanCount"));
        m.put("otherCount", num(cnt, "OTHERCOUNT", "otherCount"));
        m.put("paperItemCount", questionMapper.countPaperItems());
        return R.ok(m);
    }

    /**
     * 清空题库（物理删除，不可恢复）
     *
     * @param scope   all=全部题目；scan=仅扫描识别入库的题
     * @param confirm 必须为 CLEAR，作为二次确认，防止误调用
     */
    @DeleteMapping("/clear")
    public R<Map<String, Object>> clear(@RequestParam(defaultValue = "all") String scope,
                                        @RequestParam(defaultValue = "") String confirm) {
        if (!"CLEAR".equalsIgnoreCase(confirm)) {
            throw new BizException("确认字串不正确，已取消清空操作");
        }
        boolean onlyScan = "scan".equalsIgnoreCase(scope);
        int n = onlyScan ? questionMapper.deleteBySource("SCAN") : questionMapper.deleteAll();
        int items = questionMapper.deleteOrphanPaperItems();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", onlyScan ? "scan" : "all");
        m.put("deletedQuestions", n);
        m.put("deletedPaperItems", items);
        return R.ok(m);
    }

    private static Object num(Map<String, Object> m, String... keys) {
        if (m == null) {
            return 0;
        }
        for (String k : keys) {
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(k)) {
                    return e.getValue() == null ? 0 : e.getValue();
                }
            }
        }
        return 0;
    }
}
