package com.examscan.modules.grading.controller;

import com.examscan.common.R;
import com.examscan.modules.grading.entity.GradeSheet;
import com.examscan.modules.grading.service.GradeStatsService;
import com.examscan.modules.grading.service.GradingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/grading")
@RequiredArgsConstructor
public class GradingController {

    private final GradingService service;
    private final GradeStatsService statsService;

    /** 上传答题卡扫描件并自动阅卷 */
    @PostMapping(value = "/grade", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Map<String, Object>> grade(@RequestPart("file") MultipartFile file,
                                        @RequestParam Long paperId,
                                        @RequestParam(required = false) Long sheetId,
                                        @RequestParam(required = false) Long mergeInto,
                                        @RequestParam(required = false) Integer pageIndex,
                                        @RequestParam(required = false, defaultValue = "ocr") String mode) throws IOException {
        return R.ok(service.grade(file.getBytes(), paperId, sheetId, mode, pageIndex, mergeInto));
    }

    @GetMapping("/list")
    public R<List<GradeSheet>> list(@RequestParam(required = false) Long paperId) {
        return R.ok(service.list(paperId));
    }

    /**
     * 本场考试成绩统计：卷面总分 / 平均分 / 最高分 / 最低分 + 图表数据。
     * 以试卷界定场次，可再按班级、阅卷日期范围缩小范围。
     */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats(@RequestParam(required = false) Long paperId,
                                        @RequestParam(required = false) String className,
                                        @RequestParam(required = false) String startDate,
                                        @RequestParam(required = false) String endDate) {
        return R.ok(statsService.stats(paperId, className, startDate, endDate));
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    /** 人工复核主观题分数 */
    @PostMapping("/{id}/adjust")
    public R<GradeSheet> adjust(@PathVariable Long id,
                                @RequestBody List<Map<String, Object>> manualScores) {
        return R.ok(service.adjust(id, manualScores));
    }

    /** 补录/修改学号、班级与姓名（三者允许人工更正；三者一致则在阅卷统计中合并为一条成绩） */
    @PostMapping("/{id}/info")
    public R<GradeSheet> info(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(service.updateInfo(id, body.get("studentNo"),
                body.get("className"), body.get("studentName")));
    }

    /** 答题卡原图 */
    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        byte[] bytes = service.loadImage(id);
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(bytes);
    }

    /** 删除阅卷记录（单条/批量，body 传 ids） */
    @DeleteMapping("/{id}")
    public R<Map<String, Object>> delete(@PathVariable Long id) {
        return R.ok(Map.of("deleted", service.deleteRecords(List.of(id))));
    }

    @PostMapping("/batch-delete")
    public R<Map<String, Object>> deleteBatch(@RequestBody Map<String, List<Long>> body) {
        return R.ok(Map.of("deleted", service.deleteRecords(body.get("ids") == null ? List.of() : body.get("ids"))));
    }

    /** 单题作答区 OCR 文字（人工打分参考；识别不出返回空串，可看截图） */
    @GetMapping("/{id}/cell/{itemId}/ocr")
    public R<Map<String, Object>> cellOcr(@PathVariable Long id, @PathVariable Long itemId) {
        return R.ok(Map.of("text", service.ocrCellCrop(id, itemId)));
    }

    /** 单题作答区截图（客观题涂点位置 / 主观题答题框），供人工核对打分 */
    @GetMapping("/{id}/cell/{itemId}")
    public ResponseEntity<byte[]> cellCrop(@PathVariable Long id, @PathVariable Long itemId) {
        byte[] b = service.loadCellCrop(id, itemId);
        return b == null ? ResponseEntity.notFound().build()
                : ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(b);
    }

    /** 姓名/班级/学号选区截图及完整信息区整图（type = name | class | id | info） */
    @GetMapping("/{id}/crop/{type}")
    public ResponseEntity<byte[]> crop(@PathVariable Long id, @PathVariable String type) {
        if (!"name".equals(type) && !"class".equals(type) && !"id".equals(type) && !"info".equals(type)) {
            return ResponseEntity.badRequest().build();
        }
        byte[] bytes = service.loadCrop(id, type);
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(bytes);
    }
}
