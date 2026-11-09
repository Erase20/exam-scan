package com.examscan.modules.question.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examscan.common.QuestionType;
import com.examscan.modules.question.dto.QuestionQuery;
import com.examscan.modules.question.entity.Question;
import com.examscan.modules.question.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 搜题服务
 *
 * 三种检索方式：
 *  1. 条件筛选：题型 / 难度 / 学科 / 年级 / 知识点 / 是否含图
 *  2. 关键词检索：题干 LIKE + 多词 AND 匹配
 *  3. 相似题检索：基于 2-gram 分词 + TF-IDF 余弦相似度的轻量语义召回（无需外部向量库）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionSearchService {

    private final QuestionMapper questionMapper;

    /** 分页条件检索 */
    public Page<Question> search(QuestionQuery q) {
        Page<Question> page = new Page<>(q.getPage(), q.getSize());
        return questionMapper.selectPage(page, buildWrapper(q));
    }

    /** 按条件查出全部（不分页，供导出使用）：默认按卷内顺序，跟随表头排序 */
    public List<Question> listAll(QuestionQuery q) {
        if (q.getSortBy() == null || q.getSortBy().isBlank()) {
            q.setSortBy("seq");
        }
        return questionMapper.selectList(buildWrapper(q));
    }

    /** 筛选条件 → 查询条件（导出与检索共用） */
    public LambdaQueryWrapper<Question> buildWrapper(QuestionQuery q) {
        LambdaQueryWrapper<Question> w = new LambdaQueryWrapper<>();
        if (q.getQType() != null && !q.getQType().isBlank()) {
            w.eq(Question::getQType, q.getQType());
        }
        if (q.getSubject() != null && !q.getSubject().isBlank()) {
            w.eq(Question::getSubject, q.getSubject());
        }
        if (q.getGrade() != null && !q.getGrade().isBlank()) {
            w.eq(Question::getGrade, q.getGrade());
        }
        if (q.getDifficulty() != null) {
            w.eq(Question::getDifficulty, q.getDifficulty());
        }
        if (q.getMinDifficulty() != null) {
            w.ge(Question::getDifficulty, q.getMinDifficulty());
        }
        if (q.getMaxDifficulty() != null) {
            w.le(Question::getDifficulty, q.getMaxDifficulty());
        }
        if (q.getKnowledge() != null && !q.getKnowledge().isBlank()) {
            w.like(Question::getKnowledge, q.getKnowledge());
        }
        if (q.getHasImage() != null) {
            w.eq(Question::getHasImage, q.getHasImage());
        }
        // 课序筛选：单选/多选（IN）；或仅筛「未设置」
        if (Boolean.TRUE.equals(q.getLessonOrderEmpty())) {
            w.isNull(Question::getLessonOrder);
        } else if (q.getLessonOrder() != null && !q.getLessonOrder().isEmpty()) {
            w.in(Question::getLessonOrder, q.getLessonOrder());
        }
        if (q.getKeyword() != null && !q.getKeyword().isBlank()) {
            // 多关键词 AND：每个词都要在题干/答案里出现
            for (String kw : q.getKeyword().trim().split("\\s+")) {
                if (kw.isBlank()) {
                    continue;
                }
                w.and(x -> x.like(Question::getStem, kw)
                        .or().like(Question::getAnswer, kw)
                        .or().like(Question::getKnowledge, kw));
            }
        }
        applySort(w, q);
        return w;
    }

    // ---------------- 排序 ----------------

    /** 允许表头点击排序的列 → 数据库列（白名单，防止注入） */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "qType", "q_type",
            "subject", "subject",
            "grade", "grade",
            "knowledge", "knowledge",
            "seq", "seq",
            "lessonOrder", "lesson_order",
            "createTime", "create_time"
    );

    /**
     * 排序：表头点击的字段优先，否则按「大题顺序 → 入库时间」。
     *
     * 空值（未填学科/年级/知识点）统一排在最后，避免空值占满首屏；
     * 题型按常规卷面顺序（选择 → 填空 → 判断 → 计算 → 解答 → 作图 → 实验 → 其他）排列。
     */
    private void applySort(LambdaQueryWrapper<Question> w, QuestionQuery q) {
        // Map.of 生成的是不可变 Map，传入 null key 会直接抛 NPE，
        // 而 sortBy 是可选参数（旧客户端、脚本调用都可能不传），这里必须先判空
        String key = q == null ? null : q.getSortBy();
        String col = (key == null || key.isBlank()) ? null : SORT_COLUMNS.get(key);
        if (col == null) {
            // 扫描入库的题目按「大题顺序 → 小题号」排好 seq，优先按它展示；
            // 手工/导入的题 seq 为 0，仍按入库时间倒序
            w.orderByAsc(Question::getSeq).orderByDesc(Question::getId);
            return;
        }
        boolean asc = q.getSortOrder() == null || !"desc".equalsIgnoreCase(q.getSortOrder());
        String dir = asc ? "ASC" : "DESC";
        String expr = "q_type".equals(col) ? typeOrderExpr() : col;
        // 文本列才需要「空值排最后」；数值/时间列与空串比较会报类型错误
        boolean textCol = !"seq".equals(col) && !"create_time".equals(col) && !"lesson_order".equals(col);
        String head = textCol
                ? "CASE WHEN " + col + " IS NULL OR " + col + " = '' THEN 1 ELSE 0 END, "
                : "";
        w.last("ORDER BY " + head + expr + " " + dir + ", seq ASC, id DESC");
    }

    /** 题型排序表达式：按 QuestionType 声明顺序（即常规卷面顺序）而非字典序 */
    private String typeOrderExpr() {
        StringBuilder sb = new StringBuilder("CASE q_type");
        int i = 1;
        for (QuestionType t : QuestionType.values()) {
            sb.append(" WHEN '").append(t.name()).append("' THEN ").append(i++);
        }
        return sb.append(" ELSE ").append(i).append(" END").toString();
    }

    /**
     * 相似题推荐（题干粘贴近邻）
     * 用字符 2-gram + TF-IDF 余弦，纯 Java 实现，零外部依赖
     */
    public List<Map<String, Object>> similar(Long questionId, int topK) {
        Question base = questionMapper.selectById(questionId);
        if (base == null) {
            return List.of();
        }
        List<Question> pool = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .ne(Question::getId, questionId)
                .eq(base.getQType() != null, Question::getQType, base.getQType())
                .last("limit 3000"));
        if (pool.isEmpty()) {
            // 同题型没有足够样本时放开题型限制
            pool = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                    .ne(Question::getId, questionId)
                    .last("limit 3000"));
        }

        Map<String, Double> baseVec = vectorize(base.getStem());
        List<Map<String, Object>> scored = new ArrayList<>();
        for (Question q : pool) {
            double sim = cosine(baseVec, vectorize(q.getStem()));
            if (sim > 0.05) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("question", q);
                m.put("similarity", Math.round(sim * 10000) / 100.0);
                scored.add(m);
            }
        }
        scored.sort((a, b) -> Double.compare((Double) b.get("similarity"), (Double) a.get("similarity")));
        return scored.stream().limit(topK).collect(Collectors.toList());
    }

    /** 按题干片段直接搜相似题（用于"拍照搜题"场景的文本输入） */
    public List<Map<String, Object>> similarByText(String text, int topK) {
        List<Question> pool = questionMapper.selectList(new LambdaQueryWrapper<Question>().last("limit 3000"));
        Map<String, Double> v = vectorize(text);
        List<Map<String, Object>> scored = new ArrayList<>();
        for (Question q : pool) {
            double sim = cosine(v, vectorize(q.getStem()));
            if (sim > 0.08) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("question", q);
                m.put("similarity", Math.round(sim * 10000) / 100.0);
                scored.add(m);
            }
        }
        scored.sort((a, b) -> Double.compare((Double) b.get("similarity"), (Double) a.get("similarity")));
        return scored.stream().limit(topK).collect(Collectors.toList());
    }

    /** 题库统计概览 */
    public Map<String, Object> stats() {
        List<Question> all = questionMapper.selectList(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", all.size());
        m.put("byType", all.stream().collect(Collectors.groupingBy(
                q -> q.getQType() == null ? "OTHER" : q.getQType(), Collectors.counting())));
        m.put("byDifficulty", all.stream().collect(Collectors.groupingBy(
                q -> q.getDifficulty() == null ? 3 : q.getDifficulty(), Collectors.counting())));
        m.put("bySubject", all.stream().filter(q -> q.getSubject() != null)
                .collect(Collectors.groupingBy(Question::getSubject, Collectors.counting())));
        m.put("withImage", all.stream().filter(q -> q.getHasImage() != null && q.getHasImage() == 1).count());
        return m;
    }

    /** 题库中已设置的课序去重列表（升序），供前端筛选下拉框使用 */
    public List<Integer> distinctLessonOrders() {
        List<Object> objs = questionMapper.selectObjs(
                new LambdaQueryWrapper<Question>().select(Question::getLessonOrder).isNotNull(Question::getLessonOrder));
        return objs.stream()
                .filter(Objects::nonNull)
                .map(o -> ((Number) o).intValue())
                .distinct().sorted()
                .collect(Collectors.toList());
    }

    // ---------------- 向量化 ----------------

    /** 字符 2-gram 词频向量（中文无需分词器） */
    private Map<String, Double> vectorize(String text) {
        Map<String, Double> tf = new HashMap<>();
        if (text == null) {
            return tf;
        }
        String s = text.replaceAll("[\\s\\p{Punct}（）【】、，。；：“”‘’]", "");
        for (int i = 0; i + 2 <= s.length(); i++) {
            String g = s.substring(i, i + 2);
            tf.merge(g, 1.0, Double::sum);
        }
        // 亚线性 TF 缩放
        tf.replaceAll((k, v) -> 1.0 + Math.log(v));
        return tf;
    }

    private double cosine(Map<String, Double> a, Map<String, Double> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        double dot = 0, na = 0, nb = 0;
        for (Map.Entry<String, Double> e : a.entrySet()) {
            na += e.getValue() * e.getValue();
            Double v = b.get(e.getKey());
            if (v != null) {
                dot += e.getValue() * v;
            }
        }
        for (Double v : b.values()) {
            nb += v * v;
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
