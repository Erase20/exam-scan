package com.examscan.modules.student.controller;

import com.examscan.common.R;
import com.examscan.modules.student.entity.Student;
import com.examscan.modules.student.service.StudentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 考生管理：名单（Excel 导入 / 手动维护）+ 成绩汇总导出（考生表关联阅卷表）。
 */
@RestController
@RequestMapping("/api/student")
@RequiredArgsConstructor
public class StudentController {

    private final StudentService service;

    @GetMapping("/list")
    public R<List<Student>> list(@RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) String className) {
        return R.ok(service.list(keyword, className));
    }

    @PostMapping
    public R<Student> add(@RequestBody Map<String, String> body) {
        return R.ok(service.add(body.get("studentNo"), body.get("name"),
                body.get("className"), body.get("grade")));
    }

    @PostMapping("/{id}")
    public R<Student> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(service.update(id, body.get("studentNo"), body.get("name"),
                body.get("className"), body.get("grade")));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }

    /** Excel 名单导入（列：考号、姓名、班级、年级，首行表头自动跳过） */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Map<String, Object>> importExcel(@RequestPart("file") MultipartFile file) {
        return R.ok(service.importExcel(file));
    }

    /** 考生成绩汇总表导出（考号/姓名/班级/试卷/总分/满分/错题；paperId、className 可选过滤） */
    @GetMapping("/export/grades")
    public ResponseEntity<?> exportGrades(@RequestParam(required = false) Long paperId,
                                          @RequestParam(required = false) String className) {
        try {
            byte[] bytes = service.exportGrades(paperId, className);
            String fn = URLEncoder.encode("考生成绩汇总表.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
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
