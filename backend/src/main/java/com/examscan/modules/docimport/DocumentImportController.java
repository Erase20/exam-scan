package com.examscan.modules.docimport;

import com.examscan.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档批量导入题库：Word / Excel / PDF
 */
@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
public class DocumentImportController {

    private final DocumentImportService importService;

    /**
     * 解析文档题目，只返回可编辑草稿，不写题库
     *
     * @param file         docx/doc/xlsx/xls/pdf
     * @param subject      科目（必填）
     * @param grade        年级（必填）
     * @param qTypeOverride 题型覆盖：AUTO=自动判定，或固定题型 code
     */
    @PostMapping("/document")
    public R<ImportResult> importDocument(@RequestParam("file") MultipartFile file,
                                          @RequestParam("subject") String subject,
                                          @RequestParam("grade") String grade,
                                          @RequestParam(value = "qType", required = false, defaultValue = "AUTO") String qTypeOverride) {
        return R.ok(importService.importDocument(file, subject, grade, qTypeOverride));
    }

    /**
     * 确认入库：把前端编辑后的题目草稿写入题库
     * body: { subject, grade, items: [{qNo, qType, stem, options, answer, analysis, difficulty, score, ...}] }
     */
    @PostMapping("/confirm")
    public R<Map<String, Object>> confirm(@RequestBody Map<String, Object> body) {
        String subject = body.get("subject") == null ? "" : body.get("subject").toString();
        String grade = body.get("grade") == null ? "" : body.get("grade").toString();
        List<Map<String, Object>> items = new ArrayList<>();
        Object raw = body.get("items");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> item = (Map<String, Object>) m;
                    items.add(item);
                }
            }
        }
        return R.ok(importService.confirmQuestions(subject, grade, items));
    }

    /**
     * 下载题库导入模板（Excel）
     *
     * 模板含「题库模板」与「填写说明」两张表，列结构与题库导出一致，
     * 因此导出的文件可以直接编辑后再导入。
     */
    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        byte[] data = importService.buildTemplate();
        String fn = URLEncoder.encode("题库导入模板.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fn)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(data);
    }
}
