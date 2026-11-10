package com.examscan.modules.paper.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.examscan.common.PaperAnswerUtil;
import com.examscan.common.QuestionType;
import com.examscan.modules.paper.entity.Paper;
import com.examscan.modules.paper.entity.PaperItem;
import com.examscan.modules.paper.mapper.PaperItemMapper;
import com.examscan.modules.paper.mapper.PaperMapper;
import com.examscan.modules.question.entity.Question;
import com.examscan.modules.question.mapper.QuestionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 试卷 / 答案导出为 Word
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperExportService {

    private final PaperMapper paperMapper;
    private final PaperItemMapper itemMapper;
    private final QuestionMapper questionMapper;
    private final ObjectMapper json = new ObjectMapper();

    /** inline 模式下会把答案填入题干空位的题型（填空逐空填；选择/判断填入括号空位） */
    private static final Set<String> INLINE_FILL_TYPES = Set.of("FILL", "CHOICE", "JUDGE");

    /** 与 WebConfig 的 /files/** 映射一致，用于把题图 Web 路径还原成磁盘文件 */
    @Value("${exam-scan.storage.root:./data/storage}")
    private String storageRoot;

    /**
     * 导出试卷 Word
     *
     * @param withAnswer 是否附带参考答案与解析（答案集中排在试卷末页）
     */
    public byte[] exportDocx(Long paperId, boolean withAnswer) {
        return exportDocx(paperId, withAnswer ? "separate" : "none");
    }

    /**
     * 导出试卷 Word。
     *
     * @param answerMode 答案呈现方式：
     *                   none     —— 不含答案；
     *                   inline   —— 答案填在相应位置（填空题逐空填入题干空位，选择/判断填入括号，无空位题型题后标【答案】）；
     *                   separate —— 答案与解析集中排在试卷末页（旧行为）。
     */
    public byte[] exportDocx(Long paperId, String answerMode) {
        String mode = answerMode == null ? "none" : answerMode;
        boolean inline = "inline".equals(mode);
        boolean separate = "separate".equals(mode);
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new IllegalArgumentException("试卷不存在");
        }
        // 与详情页保持一致：先按大题再按卷内顺序，保证同题型题目连续
        List<PaperItem> items = itemMapper.selectList(new LambdaQueryWrapper<PaperItem>()
                .eq(PaperItem::getPaperId, paperId)
                .orderByAsc(PaperItem::getSectionNo)
                .orderByAsc(PaperItem::getSortNo));

        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // 页边距（A4 纵向）
            CTSectPr sectPr = doc.getDocument().getBody().isSetSectPr()
                    ? doc.getDocument().getBody().getSectPr()
                    : doc.getDocument().getBody().addNewSectPr();
            CTPageMar mar = sectPr.isSetPgMar() ? sectPr.getPgMar() : sectPr.addNewPgMar();
            mar.setLeft(BigInteger.valueOf(1134));
            mar.setRight(BigInteger.valueOf(1134));
            mar.setTop(BigInteger.valueOf(1440));
            mar.setBottom(BigInteger.valueOf(1440));

            // 标题
            XWPFParagraph title = doc.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun tr = title.createRun();
            tr.setText(paper.getTitle());
            tr.setBold(true);
            tr.setFontSize(18);
            tr.setFontFamily("宋体");

            // 副标题
            XWPFParagraph sub = doc.createParagraph();
            sub.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun sr = sub.createRun();
            StringBuilder sb = new StringBuilder();
            if (paper.getSubject() != null) {
                sb.append(paper.getSubject()).append("　");
            }
            sb.append("（满分：").append(fmt(paper.getTotalScore()))
                    .append("分　考试时间：").append(paper.getDuration()).append("分钟）");
            sr.setText(sb.toString());
            sr.setFontSize(10.5);
            sr.setFontFamily("宋体");

            XWPFParagraph info = doc.createParagraph();
            info.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun ir = info.createRun();
            ir.setText("班级：____________　姓名：____________　学号：____________　得分：________");
            ir.setFontSize(10.5);
            ir.setFontFamily("宋体");

            // 按大题分组
            Map<Integer, List<PaperItem>> sections = new LinkedHashMap<>();
            Map<Long, Question> qMap = new HashMap<>();
            List<Long> qids = items.stream().map(PaperItem::getQuestionId).distinct().toList();
            if (!qids.isEmpty()) {
                for (Question q : questionMapper.selectBatchIds(qids)) {
                    qMap.put(q.getId(), q);
                }
            }
            for (PaperItem it : items) {
                sections.computeIfAbsent(it.getSectionNo(), k -> new ArrayList<>()).add(it);
            }

            String[] cnNums = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
            int secIdx = 0;
            for (Map.Entry<Integer, List<PaperItem>> e : sections.entrySet()) {
                List<PaperItem> list = e.getValue();
                BigDecimal subtotal = list.stream().map(PaperItem::getScore)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                String secName = list.get(0).getSectionName();
                String head = (secIdx < cnNums.length ? cnNums[secIdx] : String.valueOf(secIdx + 1))
                        + "、" + secName + "（共" + list.size() + "小题，"
                        + fmt(subtotal) + "分）";
                secIdx++;

                XWPFParagraph hp = doc.createParagraph();
                hp.setSpacingBefore(200);
                XWPFRun hr = hp.createRun();
                hr.setText(head);
                hr.setBold(true);
                hr.setFontSize(12);
                hr.setFontFamily("宋体");

                int no = 1;
                for (PaperItem it : list) {
                    Question q = qMap.get(it.getQuestionId());
                    if (q == null) {
                        continue;
                    }

                    // inline 模式：把答案按空位填进题干（填空/选择/判断），其余题型保持原题干
                    String stem = safe(q.getStem()).trim();
                    List<String> answers = inline ? PaperAnswerUtil.splitAnswers(q.getAnswer()) : List.of();
                    List<String> leftover = List.of();
                    if (inline && !answers.isEmpty() && INLINE_FILL_TYPES.contains(q.getQType())) {
                        PaperAnswerUtil.FillResult fr = PaperAnswerUtil.fillBlanks(stem, answers);
                        stem = fr.filled();
                        leftover = fr.leftover();
                    }

                    XWPFParagraph p = doc.createParagraph();
                    p.setSpacingBefore(80);
                    XWPFRun r = p.createRun();
                    r.setFontSize(11);
                    r.setFontFamily("宋体");
                    r.setText(no + "．" + stem);

                    // 题库中标记为图片的区域，随题干一起插入试卷
                    appendImages(doc, q);

                    // 选项
                    List<Map<String, String>> opts = parseOptions(q.getOptions());
                    if (!opts.isEmpty()) {
                        XWPFParagraph op = doc.createParagraph();
                        op.setIndentationLeft(240);
                        XWPFRun or = op.createRun();
                        or.setFontSize(11);
                        or.setFontFamily("宋体");
                        StringBuilder os = new StringBuilder();
                        for (Map<String, String> o : opts) {
                            os.append(o.get("key")).append("．").append(o.get("content"));
                            if (!o.equals(opts.get(opts.size() - 1))) {
                                os.append("　　");
                            }
                        }
                        or.setText(os.toString());
                    }

                    // inline 模式：空位没放下的答案与解析，紧跟本题显示
                    if (inline) {
                        String ansText = null;
                        if (!answers.isEmpty()) {
                            if (INLINE_FILL_TYPES.contains(q.getQType())) {
                                ansText = leftover.isEmpty() ? null : String.join("；", leftover);
                            } else {
                                ansText = safe(q.getAnswer());
                            }
                        }
                        String analysis = safe(q.getAnalysis());
                        StringBuilder extra = new StringBuilder();
                        if (ansText != null && !ansText.isBlank()) {
                            extra.append("【答案】").append(ansText);
                        }
                        if (!analysis.isBlank()) {
                            if (extra.length() > 0) {
                                extra.append("　");
                            }
                            extra.append("【解析】").append(analysis);
                        }
                        if (extra.length() > 0) {
                            XWPFParagraph ep = doc.createParagraph();
                            ep.setIndentationLeft(240);
                            XWPFRun er = ep.createRun();
                            er.setFontSize(10);
                            er.setFontFamily("宋体");
                            er.setColor("C00000");
                            er.setText(extra.toString());
                        }
                    }
                    no++;
                }
            }

            // 参考答案（separate 模式：集中排在试卷末页）
            if (separate) {
                doc.createParagraph().createRun().addBreak(BreakType.PAGE);
                XWPFParagraph ap = doc.createParagraph();
                XWPFRun ar = ap.createRun();
                ar.setText("参考答案与解析");
                ar.setBold(true);
                ar.setFontSize(14);
                ar.setFontFamily("宋体");

                int no = 1;
                for (Map.Entry<Integer, List<PaperItem>> e : sections.entrySet()) {
                    for (PaperItem it : e.getValue()) {
                        Question q = qMap.get(it.getQuestionId());
                        if (q == null) {
                            continue;
                        }
                        XWPFParagraph p = doc.createParagraph();
                        XWPFRun r = p.createRun();
                        r.setFontSize(10.5);
                        r.setFontFamily("宋体");
                        r.setBold(true);
                        r.setText(no + "．" + safe(q.getAnswer()));
                        if (q.getAnalysis() != null && !q.getAnalysis().isBlank()) {
                            r.setBold(false);
                            r.setText("　【解析】" + q.getAnalysis());
                        }
                        no++;
                    }
                }
            }

            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("导出试卷失败", e);
            throw new IllegalStateException("导出失败: " + e.getMessage());
        }
    }

    /** 题目配图插入 Word：一图一段、居中、等比缩放到不超过 420px 宽 */
    private void appendImages(XWPFDocument doc, Question q) {
        for (Path p : resolveImagePaths(q.getImagePath())) {
            try {
                BufferedImage bi = ImageIO.read(p.toFile());
                if (bi == null || bi.getWidth() <= 0 || bi.getHeight() <= 0) {
                    continue;
                }
                double scale = Math.min(1.0, 420.0 / bi.getWidth());
                int w = Math.max(1, (int) Math.round(bi.getWidth() * scale));
                int h = Math.max(1, (int) Math.round(bi.getHeight() * scale));
                // 96dpi 下 1 像素 = 9525 EMU
                int emuW = w * 9525;
                int emuH = h * 9525;
                XWPFParagraph ip = doc.createParagraph();
                ip.setAlignment(ParagraphAlignment.CENTER);
                try (InputStream in = Files.newInputStream(p)) {
                    ip.createRun().addPicture(in, pictureType(p.getFileName().toString()),
                            p.getFileName().toString(), emuW, emuH);
                }
            } catch (Exception e) {
                log.warn("题图插入失败，跳过 {}: {}", p, e.getMessage());
            }
        }
    }

    /** imagePath（逗号分隔的 /files/... 或绝对路径）还原为真实存在的磁盘文件 */
    private List<Path> resolveImagePaths(String imagePath) {
        List<Path> out = new ArrayList<>();
        if (imagePath == null || imagePath.isBlank()) {
            return out;
        }
        Path root = Paths.get(storageRoot).toAbsolutePath().normalize();
        for (String s : imagePath.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            Path p;
            if (t.startsWith("/files/")) {
                p = root.resolve(t.substring("/files/".length()).replace('\\', '/')).normalize();
                if (!p.startsWith(root)) {
                    log.warn("题图路径越界，跳过: {}", t);
                    continue;
                }
            } else {
                p = Paths.get(t).toAbsolutePath().normalize();
            }
            if (Files.isRegularFile(p)) {
                out.add(p);
            } else {
                log.warn("题图文件不存在，跳过: {}", p);
            }
        }
        return out;
    }

    private static int pictureType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
            return XWPFDocument.PICTURE_TYPE_JPEG;
        }
        if (n.endsWith(".gif")) {
            return XWPFDocument.PICTURE_TYPE_GIF;
        }
        if (n.endsWith(".bmp")) {
            return XWPFDocument.PICTURE_TYPE_BMP;
        }
        return XWPFDocument.PICTURE_TYPE_PNG;
    }

    private List<Map<String, String>> parseOptions(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(s, new TypeReference<List<Map<String, String>>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String fmt(BigDecimal b) {
        return b == null ? "0" : b.stripTrailingZeros().toPlainString();
    }
}
