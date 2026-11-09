package com.examscan.modules.scan.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examscan.common.R;
import com.examscan.modules.scan.entity.ScanBlock;
import com.examscan.modules.scan.entity.ScanDoc;
import com.examscan.modules.scan.entity.ScanPage;
import com.examscan.modules.scan.mapper.ScanBlockMapper;
import com.examscan.modules.scan.mapper.ScanDocMapper;
import com.examscan.modules.scan.mapper.ScanPageMapper;
import com.examscan.modules.scan.service.OcrClient;
import com.examscan.modules.scan.service.ScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scan")
@RequiredArgsConstructor
public class ScanController {

    private final ScanService scanService;
    private final OcrClient ocrClient;
    private final ScanDocMapper docMapper;
    private final ScanPageMapper pageMapper;
    private final ScanBlockMapper blockMapper;

    /** OCR 服务健康检查 */
    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ocrAvailable", ocrClient.isAvailable());
        return R.ok(m);
    }

    /** 上传扫描件（不立即解析） */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<ScanDoc> upload(@RequestPart("files") MultipartFile[] files,
                             @RequestParam(required = false) String docName,
                             @RequestParam(required = false) String subject,
                             @RequestParam(required = false) String grade) {
        return R.ok(scanService.upload(files, docName, subject, grade, "PAPER"));
    }

    /**
     * 一键扫描识别：上传 -> OCR -> 图文分区 -> 题目切分 -> 入库
     */
    @PostMapping(value = "/recognize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Map<String, Object>> recognize(@RequestPart("files") MultipartFile[] files,
                                            @RequestParam(required = false) String docName,
                                            @RequestParam(required = false) String subject,
                                            @RequestParam(required = false) String grade,
                                            @RequestParam(defaultValue = "true") boolean autoSave) {
        return R.ok(scanService.scanAndSave(files, docName, subject, grade, autoSave));
    }

    /**
     * 手动框选模式：登记页面并清空旧区块（不 OCR），
     * 之后由前端在页面图上框选区域，再调用 /docs/{docId}/resplit 只识别选中区域
     */
    @PostMapping("/docs/{docId}/prepare")
    public R<Map<String, Object>> prepare(@PathVariable Long docId) {
        return R.ok(scanService.prepare(docId));
    }

    /** 重新解析已有文档 */
    @PostMapping("/parse/{docId}")
    public R<Map<String, Object>> parse(@PathVariable Long docId,
                                        @RequestParam(defaultValue = "true") boolean autoSave) {
        return R.ok(scanService.parse(docId, autoSave));
    }

    /** 文档列表 */
    @GetMapping("/docs")
    public R<Page<ScanDoc>> docs(@RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "10") int size) {
        return R.ok(docMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<ScanDoc>().orderByDesc(ScanDoc::getId)));
    }

    /** 文档详情：页面 + 图文区块（前端用于可视化预览） */
    @GetMapping("/docs/{docId}")
    public R<Map<String, Object>> docDetail(@PathVariable Long docId) {
        // 兼容手机上传的历史记录：只写了 sourceFile、没有 scan_page，打开时补齐页面
        scanService.ensurePages(docId);
        ScanDoc doc = docMapper.selectById(docId);
        List<ScanPage> pages = pageMapper.selectList(new LambdaQueryWrapper<ScanPage>()
                .eq(ScanPage::getDocId, docId).orderByAsc(ScanPage::getPageNo));
        List<ScanBlock> blocks = blockMapper.selectList(new LambdaQueryWrapper<ScanBlock>()
                .eq(ScanBlock::getDocId, docId)
                .orderByAsc(ScanBlock::getPageNo)
                .orderByAsc(ScanBlock::getSortNo));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("doc", doc);
        m.put("pages", pages);
        m.put("blocks", blocks);
        return R.ok(m);
    }

    /**
     * 按人工调整后的区域重新识别并切题
     * body: { regions:[{pageNo,x,y,w,h,blockType}], autoSave:true }
     */
    @PostMapping("/docs/{docId}/resplit")
    public R<Map<String, Object>> resplit(@PathVariable Long docId, @RequestBody Map<String, Object> body) {
        List<Map<String, Object>> regions = new ArrayList<>();
        Object raw = body.get("regions");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> item = (Map<String, Object>) m;
                    regions.add(item);
                }
            }
        }
        boolean autoSave = Boolean.parseBoolean(String.valueOf(body.getOrDefault("autoSave", "true")));
        return R.ok(scanService.resplit(docId, regions, autoSave));
    }

    /**
     * 确认入库：把核对/调整后的题目草稿（修改、复制、拆分、合并、删除的结果）写入题库。
     * 覆盖式入库：会先清理该文档已入库的历史题目。
     * body: { items:[{qNo,qType,stem,options,answer,...}] }
     */
    @PostMapping("/docs/{docId}/questions/confirm")
    public R<Map<String, Object>> confirmQuestions(@PathVariable Long docId,
                                                   @RequestBody Map<String, Object> body) {
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
        return R.ok(scanService.confirmQuestions(docId, items));
    }

    /** 页面原图：前端在其上叠加区块框与切分线 */
    @GetMapping("/docs/{docId}/pages/{pageNo}/image")
    public ResponseEntity<byte[]> pageImage(@PathVariable Long docId, @PathVariable int pageNo) {
        // 页面记录缺失时按 sourceFile 兜底，保证手机上传后立刻能看到原图
        String imagePath = scanService.pageImagePath(docId, pageNo);
        if (imagePath == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(imagePath);
            String lower = p.getFileName().toString().toLowerCase();
            MediaType mt = lower.endsWith(".png") ? MediaType.IMAGE_PNG
                    : (lower.endsWith(".gif") ? MediaType.IMAGE_GIF : MediaType.IMAGE_JPEG);
            return ResponseEntity.ok().contentType(mt).body(java.nio.file.Files.readAllBytes(p));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/docs/{docId}")
    public R<Void> deleteDoc(@PathVariable Long docId) {
        docMapper.deleteById(docId);
        return R.ok();
    }

    /** 批量删除历史文档（逻辑删除） */
    @PostMapping("/docs/batch-delete")
    public R<Map<String, Object>> deleteDocsBatch(@RequestBody Map<String, List<Long>> body) {
        List<Long> ids = body.get("ids") == null ? List.of() : body.get("ids");
        int n = 0;
        for (Long id : ids) {
            if (id != null && docMapper.deleteById(id) > 0) {
                n++;
            }
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("deleted", n);
        return R.ok(out);
    }
}
