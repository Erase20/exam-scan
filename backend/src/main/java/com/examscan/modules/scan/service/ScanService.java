package com.examscan.modules.scan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.examscan.common.BizException;
import com.examscan.modules.question.entity.Question;
import com.examscan.modules.question.mapper.QuestionMapper;
import com.examscan.modules.scan.entity.ScanBlock;
import com.examscan.modules.scan.entity.ScanDoc;
import com.examscan.modules.scan.entity.ScanPage;
import com.examscan.modules.scan.mapper.ScanBlockMapper;
import com.examscan.modules.scan.mapper.ScanDocMapper;
import com.examscan.modules.scan.mapper.ScanPageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扫描识别主流程编排：
 *   上传 -> OCR 分区 -> 区块入库 -> 文本聚合 -> 题目切分 -> 题型分类 -> 题目入库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanService {

    private final ScanDocMapper docMapper;
    private final ScanPageMapper pageMapper;
    private final ScanBlockMapper blockMapper;
    private final QuestionMapper questionMapper;
    private final OcrClient ocrClient;
    private final QuestionSplitter splitter;
    private final ObjectMapper json = new ObjectMapper();

    @Value("${exam-scan.storage.root:./data/storage}")
    private String storageRoot;

    /** 上传扫描件（可多页） */
    public ScanDoc upload(MultipartFile[] files, String docName, String subject, String grade, String docType) {
        if (files == null || files.length == 0) {
            throw new BizException("请选择要上传的图片");
        }
        ScanDoc doc = new ScanDoc();
        doc.setDocName(docName != null && !docName.isBlank() ? docName : files[0].getOriginalFilename());
        doc.setDocType(docType == null ? "PAPER" : docType);
        doc.setSubject(subject);
        doc.setGrade(grade);
        doc.setPageCount(files.length);
        doc.setStatus("UPLOADED");
        doc.setParseMs(0L);
        doc.setDeleted(0);
        docMapper.insert(doc);

        Path dir = Paths.get(storageRoot, "doc", String.valueOf(doc.getId())).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
            List<String> saved = new ArrayList<>();
            for (int i = 0; i < files.length; i++) {
                String ext = extOf(files[i].getOriginalFilename());
                Path p = dir.resolve("page-" + (i + 1) + ext);
                files[i].transferTo(p.toFile());
                saved.add(p.toString());
            }
            doc.setSourceFile(String.join(",", saved));
            docMapper.updateById(doc);
        } catch (IOException e) {
            throw new BizException("文件保存失败: " + e.getMessage());
        }
        // 上传即登记页面（不跑 OCR）：手机拍照上传后，电脑端无需先解析就能看到原图。
        // 过去只写 sourceFile，scan_page 为空，页面图接口一直 404。
        rebuildPagesFromSource(doc);
        return doc;
    }

    /**
     * 解析文档：OCR + 图文分区 + 落库（区块），并返回抽取到的题目草稿
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> parse(Long docId, boolean autoSaveQuestions) {
        ScanDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            throw new BizException("文档不存在: " + docId);
        }
        long t0 = System.currentTimeMillis();

        // 清理旧数据（支持重复解析）
        pageMapper.delete(new LambdaQueryWrapper<ScanPage>().eq(ScanPage::getDocId, docId));
        blockMapper.delete(new LambdaQueryWrapper<ScanBlock>().eq(ScanBlock::getDocId, docId));

        List<String> paths = sourcePaths(doc);
        if (paths.isEmpty()) {
            throw new BizException("文档没有可解析的图片");
        }

        List<byte[]> images = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> resolved = new ArrayList<>();
        for (String raw : paths) {
            String p = resolveImagePath(raw, docId);
            try {
                images.add(Files.readAllBytes(Paths.get(p)));
                names.add(Paths.get(p).getFileName().toString());
            } catch (IOException e) {
                throw new BizException("读取图片失败: " + p);
            }
            resolved.add(p);
        }
        doc.setSourceFile(String.join(",", resolved));
        docMapper.updateById(doc);

        Map<String, Object> ocrResult = null;
        try {
            ocrResult = ocrClient.parseDocument(images, names);
            // OCR 对大图偶发只返回部分文本（如只识别到前几题就中断），疑似残缺则自动重试
            int ocrTries = 0;
            while (!isOcrResultComplete(ocrResult, images) && ocrTries < 2) {
                log.warn("文档 {} OCR 结果疑似残缺，自动重试 ({}/{})", docId, ocrTries + 1, 2);
                ocrResult = ocrClient.parseDocument(images, names);
                ocrTries++;
            }
        } catch (Exception e) {
            log.warn("文档 {} OCR 调用失败: {}", docId, e.getMessage());
        }
        if (!isOcrResultComplete(ocrResult, images)) {
            // 手机拍照/局部卷面经常过不了整卷完整性校验。过去这里直接抛异常，
            // 事务回滚后 scan_page 为空，电脑端就成了"只有记录没有图片、重新解析无效"。
            // 现在降级为「只登记原图」：页面图始终可见，可继续用框选区域识别。
            log.warn("文档 {} 整卷 OCR 不完整或不可用，降级为仅登记原图", docId);
            List<Map<String, Object>> fallbackPages = rebuildPagesFromSource(doc);
            doc.setStatus("UPLOADED");
            doc.setParseMs(System.currentTimeMillis() - t0);
            docMapper.updateById(doc);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("docId", docId);
            out.put("pageCount", fallbackPages.size());
            out.put("blockCount", 0);
            out.put("costMs", doc.getParseMs());
            out.put("questionCount", 0);
            out.put("questions", List.of());
            out.put("savedIds", List.of());
            out.put("fullText", "");
            out.put("blocks", List.of());
            out.put("pages", fallbackPages);
            out.put("warning", "整卷 OCR 结果不完整（手机拍照、局部卷面较常见），已保留原图；请在「切分调整」里框选题目区域识别，或重拍清晰整页后再重新解析");
            return out;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pages = (List<Map<String, Object>>) ocrResult.get("pages");
        if (pages == null) {
            pages = List.of();
        }

        String engineName = ocrClient.isAvailable() ? "local" : "unknown";
        Path imgDir = Paths.get(storageRoot, "crop", String.valueOf(docId));

        List<Map<String, Object>> outBlocks = new ArrayList<>();
        List<Map<String, Object>> outPages = new ArrayList<>();

        int totalBlocks = 0;
        for (Map<String, Object> p : pages) {
            ScanPage sp = new ScanPage();
            sp.setDocId(docId);
            sp.setPageNo(intOf(p.get("pageNo"), 1));
            sp.setWidth(intOf(p.get("width"), 0));
            sp.setHeight(intOf(p.get("height"), 0));
            sp.setImagePath(sp.getPageNo() <= resolved.size() ? resolved.get(sp.getPageNo() - 1) : null);
            sp.setCreateTime(LocalDateTime.now());
            pageMapper.insert(sp);

            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", sp.getId());
            pm.put("pageNo", sp.getPageNo());
            pm.put("width", sp.getWidth());
            pm.put("height", sp.getHeight());
            outPages.add(pm);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> blocks = (List<Map<String, Object>>) p.get("blocks");
            if (blocks == null) {
                continue;
            }
            // 按「从上到下、从左到右」重排，保证切分出的题号顺序与卷面一致
            sortByReadingOrder(blocks);
            for (Map<String, Object> b : blocks) {
                String type = strOf(b.get("blockType"), "TEXT");
                String imagePath = null;
                // 非文字区块：把 base64 裁剪图落盘，便于题目引用配图
                Object b64 = b.get("imageBase64");
                if (!"TEXT".equals(type) && b64 != null && !b64.toString().isBlank()) {
                    try {
                        Files.createDirectories(imgDir);
                        Path ip = imgDir.resolve("p" + sp.getPageNo() + "-b" + totalBlocks + ".png");
                        Files.write(ip, Base64.getDecoder().decode(b64.toString()));
                        imagePath = ip.toString();
                    } catch (Exception e) {
                        log.warn("切图保存失败: {}", e.getMessage());
                    }
                }

                ScanBlock sb = new ScanBlock();
                sb.setDocId(docId);
                sb.setPageId(sp.getId());
                sb.setPageNo(sp.getPageNo());
                sb.setBlockType(type);
                sb.setX(intOf(b.get("x"), 0));
                sb.setY(intOf(b.get("y"), 0));
                sb.setW(intOf(b.get("w"), 0));
                sb.setH(intOf(b.get("h"), 0));
                sb.setSortNo(totalBlocks);   // 已按阅读顺序遍历，顺序号直接递增
                sb.setContent(strOf(b.get("content"), ""));
                sb.setConfidence(bigOf(b.get("confidence")));
                sb.setImagePath(imagePath);
                sb.setCreateTime(LocalDateTime.now());
                blockMapper.insert(sb);
                totalBlocks++;

                Map<String, Object> bm = new LinkedHashMap<>();
                bm.put("id", sb.getId());
                bm.put("pageNo", sb.getPageNo());
                bm.put("blockType", sb.getBlockType());
                bm.put("x", sb.getX());
                bm.put("y", sb.getY());
                bm.put("w", sb.getW());
                bm.put("h", sb.getH());
                bm.put("sortNo", sb.getSortNo());
                bm.put("content", sb.getContent());
                bm.put("hasCrop", sb.getImagePath() != null);
                outBlocks.add(bm);
            }
        }

        // 文本聚合：优先使用 OCR 服务给出的「全局行顺序全文」。
        // OCR 侧会把双栏/跨页的行按栏→行带统一排序，而按区块 content 拼接
        // 会让题干与选项、下一题交错，切题就会出现串题。
        Object ocrFullText = ocrResult.get("fullText");
        String fullText = ocrFullText == null ? "" : ocrFullText.toString();
        if (fullText.isBlank()) {
            fullText = buildFullText(docId);
        }
        doc.setStatus("PARSED");
        doc.setOcrEngine(engineName);
        doc.setParseMs(System.currentTimeMillis() - t0);
        docMapper.updateById(doc);

        // 题目切分 + 把图片区块的裁剪图挂到对应草稿
        List<QuestionSplitter.ParsedQuestion> parsed = splitter.split(fullText);
        attachDraftImages(docId, parsed);
        List<Question> saved = new ArrayList<>();
        if (autoSaveQuestions && !parsed.isEmpty()) {
            saved = saveQuestions(doc, parsed);
        }

        // OCR 偶尔漏掉个别页：按 sourceFile 补齐，保证每页都有原图可看
        Set<Integer> covered = new HashSet<>();
        for (Map<String, Object> pm : outPages) {
            covered.add(intOf(pm.get("pageNo"), 0));
        }
        for (int i = 1; i <= resolved.size(); i++) {
            if (!covered.contains(i)) {
                outPages.add(insertPage(doc, i, resolved.get(i - 1)));
            }
        }
        outPages.sort(Comparator.comparingInt(pm -> intOf(pm.get("pageNo"), 0)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("docId", docId);
        out.put("pageCount", outPages.size());
        out.put("blockCount", totalBlocks);
        out.put("costMs", doc.getParseMs());
        out.put("questionCount", parsed.size());
        out.put("questions", parsed);
        out.put("savedIds", saved.stream().map(Question::getId).toList());
        out.put("fullText", fullText);
        out.put("blocks", outBlocks);
        out.put("pages", outPages);
        return out;
    }

    /**
     * 按人工调整后的区域重新识别并切题。
     *
     * @param regions 区域列表，元素：{pageNo, x, y, w, h, blockType}，坐标为原图像素
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> resplit(Long docId, List<Map<String, Object>> regions, boolean autoSave) {
        ScanDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            throw new BizException("文档不存在: " + docId);
        }
        if (regions == null || regions.isEmpty()) {
            throw new BizException("没有可识别的区域");
        }
        long t0 = System.currentTimeMillis();

        // 按页分组，保持前端给出的顺序
        Map<Integer, List<Map<String, Object>>> byPage = new LinkedHashMap<>();
        for (Map<String, Object> r : regions) {
            int pageNo = intOf(r.get("pageNo"), 1);
            byPage.computeIfAbsent(pageNo, k -> new ArrayList<>()).add(r);
        }

        int totalBlocks = 0;
        List<Map<String, Object>> outBlocks = new ArrayList<>();
        for (Map.Entry<Integer, List<Map<String, Object>>> entry : byPage.entrySet()) {
            int pageNo = entry.getKey();
            ScanPage page = pageMapper.selectOne(new LambdaQueryWrapper<ScanPage>()
                    .eq(ScanPage::getDocId, docId)
                    .eq(ScanPage::getPageNo, pageNo));
            if (page == null || page.getImagePath() == null) {
                continue;
            }
            Path imgPath = Paths.get(page.getImagePath());
            if (!Files.exists(imgPath)) {
                throw new BizException("页面图片不存在: " + imgPath);
            }
            byte[] imgBytes;
            int origW = 0;
            int origH = 0;
            BufferedImage origin = null;
            try {
                imgBytes = Files.readAllBytes(imgPath);
                origin = ImageIO.read(imgPath.toFile());
                if (origin != null) {
                    origW = origin.getWidth();
                    origH = origin.getHeight();
                }
            } catch (IOException e) {
                throw new BizException("读取页面图片失败: " + imgPath);
            }
            // 前端按原图给出坐标，库内统一存 OCR 尺度坐标
            double scale = (origW > 0 && page.getWidth() != null && page.getWidth() > 0)
                    ? page.getWidth() * 1.0 / origW : 1.0;

            // 框选区域先按「从上到下、从左到右」排序，避免按绘制顺序切题导致题号乱序
            sortByReadingOrder(entry.getValue());
            Map<String, Object> resp = ocrClient.recognizeRegions(
                    imgBytes, imgPath.getFileName().toString(), entry.getValue());
            Object rs = resp.get("regions");
            if (!(rs instanceof List<?> rawList) || rawList.isEmpty()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> recognized = (List<Map<String, Object>>) rs;
            // 以区域 index 为准再排一次，保证结果顺序与排序后的区域顺序一致
            recognized.sort(Comparator.comparingInt(rr -> intOf(((Map<String, Object>) rr).get("index"), 0)));

            // 旧区块整页替换（scan_block 无逻辑删除字段，为物理删除）
            blockMapper.delete(new LambdaQueryWrapper<ScanBlock>()
                    .eq(ScanBlock::getDocId, docId)
                    .eq(ScanBlock::getPageNo, pageNo));

            int sortNo = 0;
            for (Map<String, Object> rr : recognized) {
                Map<String, Object> src = entry.getValue().get(
                        Math.min(intOf(rr.get("index"), sortNo), entry.getValue().size() - 1));
                String type = strOf(src.get("blockType"), "TEXT");
                int rx = intOf(rr.get("x"), intOf(src.get("x"), 0));
                int ry = intOf(rr.get("y"), intOf(src.get("y"), 0));
                int rw = intOf(rr.get("w"), intOf(src.get("w"), 0));
                int rh = intOf(rr.get("h"), intOf(src.get("h"), 0));

                ScanBlock sb = new ScanBlock();
                sb.setDocId(docId);
                sb.setPageId(page.getId());
                sb.setPageNo(pageNo);
                sb.setBlockType(type);
                sb.setX((int) Math.round(rx * scale));
                sb.setY((int) Math.round(ry * scale));
                sb.setW((int) Math.round(rw * scale));
                sb.setH((int) Math.round(rh * scale));
                sb.setSortNo(sortNo);
                sb.setContent(strOf(rr.get("content"), ""));
                sb.setConfidence(bigOf(rr.get("confidence")));
                sb.setImagePath(saveCrop(origin, rx, ry, rw, rh, docId, pageNo, sortNo));
                sb.setCreateTime(LocalDateTime.now());
                blockMapper.insert(sb);
                sortNo++;
                totalBlocks++;

                Map<String, Object> bm = new LinkedHashMap<>();
                bm.put("id", sb.getId());
                bm.put("pageNo", sb.getPageNo());
                bm.put("blockType", sb.getBlockType());
                // 与首次解析保持一致：对外返回 OCR 尺度坐标，前端按原图尺寸换算显示
                bm.put("x", sb.getX());
                bm.put("y", sb.getY());
                bm.put("w", sb.getW());
                bm.put("h", sb.getH());
                bm.put("sortNo", sb.getSortNo());
                bm.put("content", sb.getContent());
                bm.put("hasCrop", sb.getImagePath() != null);
                outBlocks.add(bm);
            }
        }

        String fullText = buildFullText(docId);
        List<QuestionSplitter.ParsedQuestion> parsed = splitter.split(fullText);
        attachDraftImages(docId, parsed);
        List<Question> saved = new ArrayList<>();
        if (autoSave) {
            // 物理清理该文档的历史识别题，避免重复累加
            questionMapper.deleteByDocId(docId);
            if (!parsed.isEmpty()) {
                saved = saveQuestions(doc, parsed);
            }
        }
        doc.setStatus("PARSED");
        doc.setParseMs(System.currentTimeMillis() - t0);
        docMapper.updateById(doc);

        List<Map<String, Object>> outPages = pageMapper.selectList(new LambdaQueryWrapper<ScanPage>()
                        .eq(ScanPage::getDocId, docId).orderByAsc(ScanPage::getPageNo))
                .stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("pageNo", p.getPageNo());
                    m.put("width", p.getWidth());
                    m.put("height", p.getHeight());
                    return m;
                })
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("docId", docId);
        out.put("pageCount", outPages.size());
        out.put("blockCount", totalBlocks);
        out.put("costMs", doc.getParseMs());
        out.put("questionCount", parsed.size());
        out.put("questions", parsed);
        out.put("savedIds", saved.stream().map(Question::getId).toList());
        out.put("fullText", fullText);
        out.put("blocks", outBlocks);
        out.put("pages", outPages);
        return out;
    }

    /**
     * 手动框选模式：只登记页面与尺寸，不跑 OCR。
     *
     * 整卷自动 OCR 每页要几十秒，而用户往往只关心某几块区域。
     * 这里上传后秒级返回页面信息，由前端在页面图上框选区域，
     * 再调用 resplit 只识别被选中的区域。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> prepare(Long docId) {
        ScanDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            throw new BizException("文档不存在: " + docId);
        }
        long t0 = System.currentTimeMillis();

        blockMapper.delete(new LambdaQueryWrapper<ScanBlock>().eq(ScanBlock::getDocId, docId));
        pageMapper.delete(new LambdaQueryWrapper<ScanPage>().eq(ScanPage::getDocId, docId));

        if (sourcePaths(doc).isEmpty()) {
            throw new BizException("文档没有可解析的图片");
        }
        List<Map<String, Object>> outPages = rebuildPagesFromSource(doc);

        doc.setStatus("UPLOADED");
        doc.setParseMs(System.currentTimeMillis() - t0);
        docMapper.updateById(doc);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("docId", docId);
        out.put("pageCount", outPages.size());
        out.put("blockCount", 0);
        out.put("questionCount", 0);
        out.put("questions", List.of());
        out.put("savedIds", List.of());
        out.put("fullText", "");
        out.put("blocks", List.of());
        out.put("pages", outPages);
        out.put("costMs", doc.getParseMs());
        return out;
    }

    /**
     * 兼容历史上传记录：早期 /scan/upload 只写 sourceFile、不建 scan_page，
     * 导致手机上传后电脑端看不到页面图。打开文档时按需补齐，不跑 OCR。
     */
    @Transactional(rollbackFor = Exception.class)
    public void ensurePages(Long docId) {
        ScanDoc doc = docMapper.selectById(docId);
        if (doc == null || sourcePaths(doc).isEmpty()) {
            return;
        }
        List<ScanPage> existing = pageMapper.selectList(new LambdaQueryWrapper<ScanPage>()
                .eq(ScanPage::getDocId, docId));
        if (!existing.isEmpty()) {
            return;
        }
        rebuildPagesFromSource(doc);
        docMapper.updateById(doc);
    }

    /** 页面原图路径：优先 scan_page，记录缺失时回退到 sourceFile 的第 N 页 */
    public String pageImagePath(Long docId, int pageNo) {
        ScanPage page = pageMapper.selectOne(new LambdaQueryWrapper<ScanPage>()
                .eq(ScanPage::getDocId, docId)
                .eq(ScanPage::getPageNo, pageNo));
        if (page != null && page.getImagePath() != null) {
            Path p = Paths.get(resolveImagePath(page.getImagePath(), docId));
            if (Files.exists(p)) {
                return p.toString();
            }
        }
        ScanDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            return null;
        }
        List<String> paths = sourcePaths(doc);
        if (pageNo < 1 || pageNo > paths.size()) {
            return null;
        }
        Path p = Paths.get(resolveImagePath(paths.get(pageNo - 1), docId));
        return Files.exists(p) ? p.toString() : null;
    }

    /** sourceFile 是以逗号分隔的多页路径 */
    private static List<String> sourcePaths(ScanDoc doc) {
        String sf = doc == null ? null : doc.getSourceFile();
        if (sf == null || sf.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : sf.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 历史数据可能是相对路径，或后端换过工作目录/存储根目录，这里解析成当前可读的绝对路径 */
    private String resolveImagePath(String raw, Long docId) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        Path p = Paths.get(raw);
        if (Files.exists(p)) {
            return p.toAbsolutePath().normalize().toString();
        }
        Path fallback = Paths.get(storageRoot, "doc", String.valueOf(docId), p.getFileName().toString());
        if (Files.exists(fallback)) {
            return fallback.toAbsolutePath().normalize().toString();
        }
        return raw;
    }

    /** 按 sourceFile 登记每页原图（调用方负责清理旧页面），返回对外页面信息 */
    private List<Map<String, Object>> rebuildPagesFromSource(ScanDoc doc) {
        List<Map<String, Object>> outPages = new ArrayList<>();
        List<String> fixed = new ArrayList<>();
        int pageNo = 0;
        for (String raw : sourcePaths(doc)) {
            pageNo++;
            String p = resolveImagePath(raw, doc.getId());
            fixed.add(p);
            outPages.add(insertPage(doc, pageNo, p));
        }
        if (!fixed.isEmpty()) {
            doc.setSourceFile(String.join(",", fixed));
            docMapper.updateById(doc);
        }
        return outPages;
    }

    /** 插入一条页面记录，尺寸取自原图，供前端叠加区块框时换算坐标 */
    private Map<String, Object> insertPage(ScanDoc doc, int pageNo, String imagePath) {
        int w = 0;
        int h = 0;
        try {
            BufferedImage bi = ImageIO.read(Paths.get(imagePath).toFile());
            if (bi != null) {
                w = bi.getWidth();
                h = bi.getHeight();
            }
        } catch (IOException e) {
            log.warn("读取页面图片失败: {}", imagePath);
        }
        ScanPage sp = new ScanPage();
        sp.setDocId(doc.getId());
        sp.setPageNo(pageNo);
        sp.setWidth(w);
        sp.setHeight(h);
        sp.setImagePath(imagePath);
        sp.setCreateTime(LocalDateTime.now());
        pageMapper.insert(sp);

        Map<String, Object> pm = new LinkedHashMap<>();
        pm.put("id", sp.getId());
        pm.put("pageNo", pageNo);
        pm.put("width", w);
        pm.put("height", h);
        return pm;
    }

    /**
     * 按「从上到下、从左到右」的阅读顺序排序。
     * 先按 y 中心排序，再把垂直方向有重叠的块聚成同一"行带"，行带内按 x 升序。
     */
    private static void sortByReadingOrder(List<Map<String, Object>> items) {
        List<Map<String, Object>> byY = new ArrayList<>(items);
        byY.sort(Comparator
                .comparingInt((Map<String, Object> m) -> intOf(m.get("pageNo"), 1))
                .thenComparingInt(m -> intOf(m.get("y"), 0) + intOf(m.get("h"), 0) / 2));
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> row = new ArrayList<>();
        int rowTop = -1;
        int rowBottom = -1;
        int rowPage = -1;
        for (Map<String, Object> m : byY) {
            int page = intOf(m.get("pageNo"), 1);
            int y = intOf(m.get("y"), 0);
            int h = Math.max(1, intOf(m.get("h"), 1));
            if (row.isEmpty() || page != rowPage || y >= rowBottom || y + h <= rowTop) {
                flushRow(out, row);
                row = new ArrayList<>();
                rowTop = y;
                rowBottom = y + h;
                rowPage = page;
            } else {
                rowTop = Math.min(rowTop, y);
                rowBottom = Math.max(rowBottom, y + h);
            }
            row.add(m);
        }
        flushRow(out, row);
        items.clear();
        items.addAll(out);
    }

    /** 行带内按 x 升序输出 */
    private static void flushRow(List<Map<String, Object>> out, List<Map<String, Object>> row) {
        if (row.isEmpty()) {
            return;
        }
        row.sort(Comparator.comparingInt(m -> intOf(m.get("x"), 0)));
        out.addAll(row);
    }

    /** 裁剪区域并落盘（供题目引用配图），失败时返回 null */
    private String saveCrop(BufferedImage origin, int x, int y, int w, int h,
                            Long docId, int pageNo, int sortNo) {
        if (origin == null || w <= 0 || h <= 0) {
            return null;
        }
        try {
            int x1 = Math.max(0, x), y1 = Math.max(0, y);
            int x2 = Math.min(origin.getWidth(), x + w), y2 = Math.min(origin.getHeight(), y + h);
            int cw = x2 - x1, ch = y2 - y1;
            if (cw <= 0 || ch <= 0) {
                return null;
            }
            BufferedImage crop = new BufferedImage(cw, ch,
                    origin.getType() == 0 ? BufferedImage.TYPE_INT_RGB : origin.getType());
            Graphics2D g = crop.createGraphics();
            g.drawImage(origin, 0, 0, cw, ch, x1, y1, x2, y2, null);
            g.dispose();
            Path dir = Paths.get(storageRoot, "crop", String.valueOf(docId));
            Files.createDirectories(dir);
            Path out = dir.resolve("split-p" + pageNo + "-b" + sortNo + ".png");
            ImageIO.write(crop, "png", out.toFile());
            return out.toString();
        } catch (Exception e) {
            log.warn("区域切图保存失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * OCR 结果完整性校验：扫描试卷的正常识别应包含多个大题（「一、」「二、」…），
     * 残缺识别往往只吐出试卷开头一部分（如只到第 4 题），后续题目（含第 7 题）被漏掉。
     * 因此以"大题标题数量"为主指标：不足 2 个视为残缺，并自动重试。
     */
    private boolean isOcrResultComplete(Map<String, Object> ocrResult, List<byte[]> images) {
        if (ocrResult == null) {
            return false;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pages = (List<Map<String, Object>>) ocrResult.get("pages");
        if (pages == null || pages.isEmpty()) {
            return false;
        }
        int textChars = 0;
        int sectionTitles = 0;
        Pattern secPat = Pattern.compile("[一二三四五六七八九十]{1,3}[、.．]");
        for (Object p : pages) {
            if (!(p instanceof Map<?, ?> pm)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> page = (Map<String, Object>) pm;
            Object bs = page.get("blocks");
            if (bs instanceof List<?> raw) {
                for (Object o : raw) {
                    if (!(o instanceof Map<?, ?> bm)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> b = (Map<String, Object>) bm;
                    if (!"TEXT".equals(strOf(b.get("blockType"), ""))) {
                        continue;
                    }
                    String c = strOf(b.get("content"), "");
                    textChars += c.length();
                    Matcher m = secPat.matcher(c);
                    while (m.find()) {
                        sectionTitles++;
                    }
                }
            }
        }
        // 文字过少，显然为残缺识别
        if (textChars < 80) {
            return false;
        }
        // 大题标题过少"且"文字量偏少：典型为只识别到试卷开头一小段（如只到第 4 题）。
        // 只用"标题数<2"会误伤"整页只有一个大题但文字充足"的正常页，故叠加文字量判断。
        if (sectionTitles < 2 && textChars < 500) {
            return false;
        }
        return true;
    }

    /** 把文档的所有文字区块按页、按阅读顺序拼成全文 */
    public String buildFullText(Long docId) {
        List<ScanBlock> blocks = blockMapper.selectList(
                new LambdaQueryWrapper<ScanBlock>()
                        .eq(ScanBlock::getDocId, docId)
                        .orderByAsc(ScanBlock::getPageNo)
                        .orderByAsc(ScanBlock::getSortNo));
        StringBuilder sb = new StringBuilder();
        for (ScanBlock b : blocks) {
            if ("TEXT".equals(b.getBlockType()) && b.getContent() != null && !b.getContent().isBlank()) {
                sb.append(b.getContent().trim()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 确认入库：把人工核对/调整（修改、复制、拆分、合并、删除）后的题目草稿写入题库。
     *
     * 覆盖式入库：先物理清理该文档历史识别题，避免重复累加。
     *
     * @param items 前端草稿列表，元素字段与 ParsedQuestion 一致：
     *              qNo/qType/sectionOrder/sectionTitle/seq/stem/options/answer/
     *              analysis/difficulty/knowledge/score/hasImage/imagePath
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirmQuestions(Long docId, List<Map<String, Object>> items) {
        ScanDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            throw new BizException("文档不存在: " + docId);
        }
        if (items == null || items.isEmpty()) {
            throw new BizException("没有可入库的题目");
        }
        questionMapper.deleteByDocId(docId);
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Question q = new Question();
            q.setDocId(docId);
            q.setQNo(strOf(item.get("qNo"), ""));
            q.setQType(strOf(item.get("qType"), "UNKNOWN"));
            q.setSectionOrder(intOf(item.get("sectionOrder"), 0));
            q.setSectionTitle(strOf(item.get("sectionTitle"), ""));
            Object seq = item.get("seq");
            q.setSeq(seq == null ? null : intOf(seq, 0));
            q.setStem(strOf(item.get("stem"), ""));
            Object options = item.get("options");
            try {
                q.setOptions(options == null ? "[]" : json.writeValueAsString(options));
            } catch (Exception e) {
                q.setOptions("[]");
            }
            q.setAnswer(strOf(item.get("answer"), ""));
            q.setAnalysis(strOf(item.get("analysis"), ""));
            q.setDifficulty(intOf(item.get("difficulty"), 3));
            q.setScore(BigDecimal.valueOf(dblOf(item.get("score"), 5.0)));
            q.setSubject(doc.getSubject());
            q.setGrade(doc.getGrade());
            q.setKnowledge(strOf(item.get("knowledge"), ""));
            String imagePath = strOf(item.get("imagePath"), "");
            q.setHasImage(imagePath.isBlank() ? 0 : 1);
            q.setImagePath(imagePath.isBlank() ? null : imagePath);
            Object lessonOrder = item.get("lessonOrder");
            if (lessonOrder != null) {
                q.setLessonOrder(intOf(lessonOrder, 0));
            }
            q.setSource("SCAN");
            q.setStatus("NORMAL");
            q.setDeleted(0);
            questionMapper.insert(q);
            ids.add(q.getId());
        }
        log.info("文档 {} 确认入库题目 {} 道（含配图 {} 道）", docId, items.size(),
                items.stream().filter(i -> !strOf(i.get("imagePath"), "").isBlank()).count());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported", items.size());
        out.put("docId", docId);
        out.put("ids", ids);
        return out;
    }

    private static double dblOf(Object o, double def) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }

    /** 把解析出的题目落库 */
    @Transactional(rollbackFor = Exception.class)
    public List<Question> saveQuestions(ScanDoc doc, List<QuestionSplitter.ParsedQuestion> parsed) {
        List<Question> out = new ArrayList<>();
        // 切分时把「标记为图片」的区域按阅读顺序归属到对应题目，随题目一并入库
        Map<Integer, List<String>> imagesByQuestion = mapImagesToQuestions(doc.getId(), parsed);
        for (int idx = 0; idx < parsed.size(); idx++) {
            QuestionSplitter.ParsedQuestion pq = parsed.get(idx);
            Question q = new Question();
            q.setDocId(doc.getId());
            q.setQNo(pq.getQNo());
            q.setQType(pq.getQType());
            // 题型大类归属：便于按「一、填空题 / 二、判断题」这样的结构分组入库与展示
            q.setSectionOrder(pq.getSectionOrder());
            q.setSectionTitle(pq.getSectionTitle());
            q.setSeq(pq.getSeq());
            q.setStem(pq.getStem());
            try {
                q.setOptions(json.writeValueAsString(pq.getOptions()));
            } catch (Exception e) {
                q.setOptions("[]");
            }
            q.setAnswer(pq.getAnswer());
            q.setAnalysis(pq.getAnalysis());
            q.setDifficulty(pq.getDifficulty());
            q.setScore(BigDecimal.valueOf(pq.getScore() == null ? 5.0 : pq.getScore()));
            q.setSubject(doc.getSubject());
            q.setGrade(doc.getGrade());
            q.setKnowledge(pq.getKnowledge());
            List<String> imgs = imagesByQuestion.getOrDefault(idx, List.of());
            String path = !imgs.isEmpty() ? String.join(",", imgs) : pq.getImagePath();
            if (path != null && !path.isBlank()) {
                q.setHasImage(1);
                q.setImagePath(path);
            } else {
                q.setHasImage(pq.isHasImage() ? 1 : 0);
            }
            q.setSource("SCAN");
            q.setStatus("NORMAL");
            q.setDeleted(0);
            questionMapper.insert(q);
            out.add(q);
        }
        log.info("文档 {} 入库题目 {} 道", doc.getId(), out.size());
        return out;
    }

    /**
     * 把切分出的图片区块挂到题目草稿上。
     *
     * 主界面走「autoSave=false → 草稿 → 确认入库」，过去图片归属只在 saveQuestions 里算，
     * 草稿里没有图片路径，确认入库时图片就丢了。这里在切题后统一计算并写入 DTO，
     * 前端可预览、可增删，confirmQuestions 会把最终 imagePath 一并落库。
     */
    private void attachDraftImages(Long docId, List<QuestionSplitter.ParsedQuestion> parsed) {
        if (parsed == null || parsed.isEmpty()) {
            return;
        }
        Map<Integer, List<String>> imagesByQuestion = mapImagesToQuestions(docId, parsed);
        for (Map.Entry<Integer, List<String>> e : imagesByQuestion.entrySet()) {
            int idx = e.getKey();
            if (idx < 0 || idx >= parsed.size() || e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            String path = String.join(",", e.getValue());
            if (!path.isBlank()) {
                parsed.get(idx).setImagePath(path);
                parsed.get(idx).setHasImage(true);
            }
        }
    }

    /**
     * 把版面分析标记为 IMAGE 的区域，按「阅读顺序」归属到切分出的题目。
     *
     * 做法：先用题干前缀在文本块中顺序定位每题的起始块；图片块归属于它之前
     * 最近一个已开始的题目（同一题可包含多张图）。返回 题目下标 -> 图片Web路径。
     */
    private Map<Integer, List<String>> mapImagesToQuestions(Long docId,
            List<QuestionSplitter.ParsedQuestion> parsed) {
        Map<Integer, List<String>> result = new LinkedHashMap<>();
        if (parsed == null || parsed.isEmpty()) {
            return result;
        }
        List<ScanBlock> blocks = blockMapper.selectList(new LambdaQueryWrapper<ScanBlock>()
                .eq(ScanBlock::getDocId, docId)
                .orderByAsc(ScanBlock::getPageNo)
                .orderByAsc(ScanBlock::getSortNo));
        if (blocks.isEmpty()) {
            return result;
        }
        // 整页被误判成图片时，那个「图片」其实是扫描背景，不能挂到题目上
        Map<Integer, int[]> pageSizes = new LinkedHashMap<>();
        for (ScanPage p : pageMapper.selectList(new LambdaQueryWrapper<ScanPage>()
                .eq(ScanPage::getDocId, docId))) {
            if (p.getPageNo() != null && p.getWidth() != null && p.getHeight() != null) {
                pageSizes.put(p.getPageNo(), new int[]{p.getWidth(), p.getHeight()});
            }
        }

        // 1) 定位每道题的起始文本块（题干前缀匹配，失败则沿用当前位置）
        int[] startBlock = new int[parsed.size()];
        int cursor = 0;
        int prev = 0;
        for (int qi = 0; qi < parsed.size(); qi++) {
            String key = stemKey(parsed.get(qi).getStem());
            int found = -1;
            if (!key.isEmpty()) {
                for (int j = cursor; j < blocks.size(); j++) {
                    ScanBlock b = blocks.get(j);
                    if (!"TEXT".equals(b.getBlockType()) || b.getContent() == null) {
                        continue;
                    }
                    if (compactText(b.getContent()).contains(key)) {
                        found = j;
                        break;
                    }
                }
            }
            if (found < 0) {
                found = Math.min(cursor, blocks.size() - 1);
            }
            // 起始块必须单调，避免个别题干匹配失败导致后续图片归属倒挂
            found = Math.max(found, prev);
            startBlock[qi] = found;
            prev = found;
            cursor = found;
        }

        // 2) 图片块归属于它之前最近一个已开始的题目
        for (int i = 0; i < blocks.size(); i++) {
            ScanBlock b = blocks.get(i);
            if (!"IMAGE".equals(b.getBlockType())
                    || b.getImagePath() == null || b.getImagePath().isBlank()) {
                continue;
            }
            int bw = b.getW() == null ? 0 : b.getW();
            int bh = b.getH() == null ? 0 : b.getH();
            if (bw < 30 || bh < 30) {
                continue;  // 碎片噪声
            }
            int[] size = pageSizes.get(b.getPageNo());
            if (size != null && size[0] > 0 && size[1] > 0
                    && (double) bw * bh / (size[0] * size[1]) > 0.6) {
                continue;  // 几乎整页的「图片」是版面误判，不是题目插图
            }
            int owner = 0;
            for (int qi = 0; qi < startBlock.length; qi++) {
                if (startBlock[qi] <= i) {
                    owner = qi;
                } else {
                    break;
                }
            }
            result.computeIfAbsent(owner, k -> new ArrayList<>())
                    .add(toWebPath(b.getImagePath()));
        }
        return result;
    }

    /** 题干特征串：取前 10 个字母/数字，规避标点、空白与填空占位符差异 */
    private static String stemKey(String stem) {
        if (stem == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stem.length() && sb.length() < 10; i++) {
            char c = stem.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.length() >= 4 ? sb.toString() : "";
    }

    /** 去掉空白与标点，便于题干与 OCR 原文做宽松匹配 */
    private static String compactText(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 存储绝对路径 -> 浏览器可访问的 /files/... 路径 */
    private String toWebPath(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        try {
            Path root = Paths.get(storageRoot).toAbsolutePath().normalize();
            Path p = Paths.get(path).toAbsolutePath().normalize();
            if (p.startsWith(root)) {
                return "/files/" + root.relativize(p).toString().replace('\\', '/');
            }
        } catch (Exception e) {
            log.warn("题图路径转换失败: {}", e.getMessage());
        }
        return path.replace('\\', '/');
    }

    /** 整卷解析：上传即识别并入库（一步完成） */
    public Map<String, Object> scanAndSave(MultipartFile[] files, String docName, String subject,
                                           String grade, boolean autoSave) {
        ScanDoc doc = upload(files, docName, subject, grade, "PAPER");
        return parse(doc.getId(), autoSave);
    }

    private static String extOf(String name) {
        if (name == null) {
            return ".png";
        }
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(i).toLowerCase() : ".png";
    }

    private static int intOf(Object o, int def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return (int) Double.parseDouble(o.toString());
        } catch (Exception e) {
            return def;
        }
    }

    private static String strOf(Object o, String def) {
        return o == null ? def : o.toString();
    }

    private static BigDecimal bigOf(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return BigDecimal.valueOf(Double.parseDouble(o.toString()));
        } catch (Exception e) {
            return null;
        }
    }
}
