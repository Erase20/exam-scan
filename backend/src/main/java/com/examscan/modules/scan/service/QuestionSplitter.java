package com.examscan.modules.scan.service;

import com.examscan.common.QuestionType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 题目切分与题型自动分类器
 *
 * 处理流程：
 *  1. 清洗 OCR 文本（全角转半角、去页眉页脚、合并断行）
 *  2. 识别"大题标题"（一、选择题（每题2分，共20分））切换当前题型上下文
 *  3. 识别"小题起首"（1. / 1、 / (1) / 12．）切分出题目
 *  4. 在题内继续拆分：选项 A. B. C. D. / 答案 / 解析 / 【解答】
 *  5. 结合大题上下文 + 题干特征 + 选项存在性 综合判定题型
 *  6. 通过特征词推断知识点与难度
 */
@Slf4j
@Service
public class QuestionSplitter {

    /** 大题标题：一、选择题（每题2分，共20分） */
    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "^\\s*([一二三四五六七八九十]{1,3})\\s*[、.．,，]\\s*(.{2,30}?)(?:[（(].*?[）)])\\s*$"
    );

    /**
     * 备用：更宽松的大题标题匹配。
     * OCR 经常丢掉标题末尾的右括号（"一、填空题。（每空2分,共20分"），
     * 因此这里不要求括号闭合，是否算大题标题由题型关键词判定兜底。
     */
    private static final Pattern SECTION_LOOSE = Pattern.compile(
            "^\\s*([一二三四五六七八九十]{1,3})\\s*[、.．,，]\\s*(.{2,24})$"
    );

    /**
     * 粘在题尾的大题标题：「…有着不同的用途。 三、正确答案我会选。(将正确答案…)」
     *
     * 双栏排版时，下一个大题标题常被 OCR 拼到上一题最后一行的末尾，
     * 开头不再是行首，就识别不出大题切换，整卷会被归成一个大题。
     */
    private static final Pattern SECTION_INLINE = Pattern.compile(
            "[。．.；;）)]\\s*([一二三四五六七八九十]{1,3})\\s*[、.．]\\s*"
                    + "((?:选择|填空|判断|计算|解答|作图|绘图|画图|实验|探究|简答|应用|综合|阅读|写作)"
                    + "[\\u4e00-\\u9fa5]{0,10})");

    /** 大题标题前缀匹配（标题后可能跟很长的括号说明，不必整行匹配） */
    private static final Pattern SECTION_PREFIX = Pattern.compile(
            "^\\s*([一二三四五六七八九十]{1,3})\\s*[、.．,，]\\s*([^（(。．.]{2,12})");

    /** 小题起首（必须在一行开头）：1. / 1、 / 1． / (1) / 1) */
    private static final Pattern[] Q_STARTS = {
            Pattern.compile("^\\s*(?<!\\d)(\\d{1,2})\\s*[.．、]\\s*(?!\\d)"),
            Pattern.compile("^\\s*[（(]\\s*(\\d{1,2})\\s*[）)]\\s*(?!\\d)"),
            Pattern.compile("^\\s*(?<!\\d)(\\d{1,2})\\s*\\)\\s*(?!\\d)"),
    };

    /**
     * 行内并排小题：只认「1. / 2、」这种带点或顿号的题号。
     *
     * 刻意不含「(1)(2)」括号形式——试卷里括号编号绝大多数是同一道题的多个小问
     * （如「（1）____ （2）____」），把它们拆成独立题会让一道填空题变成好几道。
     */
    private static final Pattern Q_START_INLINE = Pattern.compile(
            "(?<!\\d)(\\d{1,2})\\s*[.．、]\\s*(?!\\d)");

    /** 选项行：A. xxx  A、xxx  A．xxx （可能一行多个选项） */
    private static final Pattern OPTION_PATTERN = Pattern.compile(
            "([A-DＡ-Ｄ])\\s*[.．、:：]\\s*([^A-DＡ-Ｄ]{0,200}?)(?=\\s*[A-DＡ-Ｄ]\\s*[.．、:：]|$)"
    );

    private static final Pattern ANSWER_PATTERN = Pattern.compile(
            "^\\s*[【\\[]?\\s*(?:答案|参考答案|答)\\s*[】\\]]?\\s*[:：]?\\s*(.*)$"
    );

    private static final Pattern ANALYSIS_PATTERN = Pattern.compile(
            "^\\s*[【\\[]?\\s*(?:解析|分析|详解|考点|点评|点睛)\\s*[】\\]]?\\s*[:：]?\\s*(.*)$"
    );

    /** 页眉页脚噪声 */
    private static final Pattern NOISE_PATTERN = Pattern.compile(
            "^(第\\s*\\d+\\s*页|共\\s*\\d+\\s*页|.*?试卷\\s*第.*页|绝密★?启用前|.*?年级.*?数学.*?试卷|.*?考试.*?答题卡)",
            Pattern.CASE_INSENSITIVE
    );

    @Data
    public static class ParsedQuestion {
        @JsonProperty("qNo")
        private String qNo;
        @JsonProperty("qType")
        private String qType;
        /** 所属大题（题型大类）序号，按卷内顺序 1..N */
        @JsonProperty("sectionOrder")
        private Integer sectionOrder;
        /** 所属大题中文序号，如「一」 */
        @JsonProperty("sectionNo")
        private String sectionNo;
        /** 所属大题标题，如「填空题」 */
        @JsonProperty("sectionTitle")
        private String sectionTitle;
        /** 卷内连续序号 */
        @JsonProperty("seq")
        private Integer seq;
        private String stem;
        private List<Map<String, String>> options = new ArrayList<>();
        private String answer = "";
        private String analysis = "";
        private Integer difficulty = 3;
        private String knowledge = "";
        private Double score = 5.0;
        private boolean hasImage;
        /** 归属到本题的配图 Web 路径（多个用英文逗号分隔），随草稿返回前端并在确认入库时落库 */
        private String imagePath;
        /** 结构化导入时表格里自带的学科/年级；需随草稿回传，确认入库时优先使用 */
        private String subject;
        private String grade;
        /** 课序（导入模板可选填写，整数；扫描流程不使用） */
        private Integer lessonOrder;
        /** 大题标题判定的题型（内部用，用于统一同一大题的题型） */
        @JsonIgnore
        private String sectionTypeHint;
    }

    /**
     * 主入口：把 OCR 全文切成题目列表
     *
     * @param fullText OCR 全文（行以 \n 分隔）
     * @return 题目列表
     */
    public List<ParsedQuestion> split(String fullText) {
        if (fullText == null || fullText.isBlank()) {
            return Collections.emptyList();
        }
        // 同一行里挤了多道小题时先拆行，保证"一个题号 = 一道题"；
        // 被拼到题尾的大题标题也先切成独立行，否则大类切换会丢失
        List<String> lines = expandInline(splitInlineSections(cleanLines(fullText)));
        List<RawQuestion> raws = new ArrayList<>();

        // 当前大题（题型大类）上下文
        int sectionOrder = 0;
        String sectionType = null;
        String sectionNo = null;
        String sectionTitle = null;
        Double sectionScore = null;
        RawQuestion cur = null;

        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }

            // 1. 大题标题（如「一、填空题。（每空2分,共20分）」）
            SectionInfo sec = matchSection(t);
            if (sec != null && sec.type != null) {
                cur = null;
                sectionOrder++;
                sectionType = sec.type;
                sectionNo = sec.cnNo;
                sectionTitle = sec.title;
                sectionScore = extractScore(t);
                continue;
            }

            // 2. 小题起首：以题号作为切分单元
            Matcher qm = qStartMatcher(t);
            if (qm != null) {
                String no = String.valueOf(Integer.parseInt(qm.group(1)));
                RawQuestion last = raws.isEmpty() ? null : raws.get(raws.size() - 1);
                // 同一大题里再次出现同一题号：只有"紧接在上一条"时才视为内容被割裂而合并。
                // 若放宽到任意位置，后一大题的 1、2、3 会被并进前一大题的同号题里，造成串题。
                if (last != null && last.sectionOrder == sectionOrder && no.equals(last.qNo)) {
                    cur = last;
                } else if (isNoiseNumber(last, sectionOrder, no, t)) {
                    // 页脚页码、行号等被误当成题号：并入当前题或丢弃，避免切出假题
                    if (cur == null) {
                        continue;
                    }
                } else {
                    cur = new RawQuestion(sectionOrder, no, sectionType, sectionScore,
                            sectionNo, sectionTitle);
                    raws.add(cur);
                }
                String rest = t.substring(qm.end()).trim();
                if (!rest.isEmpty()) {
                    cur.lines.add(rest);
                }
                continue;
            }

            // 3. 普通行，累加到当前题
            if (cur == null) {
                // 整段开头就没有题号：多因框选区域把题号框在了外面，或 OCR 漏识别了题号。
                // 这里必须兜底建题——否则这些行无处可去，会在下面被静默丢弃，
                // 表现出来就是"区域越小越容易漏掉一两道题"。
                cur = new RawQuestion(sectionOrder, null, sectionType, sectionScore,
                        sectionNo, sectionTitle);
                raws.add(cur);
            }
            cur.lines.add(t);
        }

        // 保持 OCR 阅读顺序，只按大题归组（稳定排序）。
        // 不再按题号数字重排：多区域框选时每块区域的题号都从 1 开始，
        // 按题号排序会把第二块的题插到第一块中间，造成串题。
        raws.sort(Comparator.comparingInt((RawQuestion r) -> r.sectionOrder));

        List<ParsedQuestion> result = new ArrayList<>();
        for (RawQuestion r : raws) {
            if (!r.lines.isEmpty()) {
                result.add(build(r.qNo, r.sectionOrder, r.sectionNo, r.sectionTitle,
                        r.sectionType, r.sectionScore, r.lines));
            }
        }

        // 后处理：碎片题的取舍原则是"宁并勿弃"
        mergeFragments(result);
        // 同一大题的小题统一题型：以大题标题为准，标题缺失时用该大题内的多数题型
        unifySectionTypes(result);
        // 卷内连续序号（按大题顺序 → 小题号）；无题号的题用序号补上
        for (int i = 0; i < result.size(); i++) {
            ParsedQuestion q = result.get(i);
            q.setSeq(i + 1);
            if (q.getQNo() == null || q.getQNo().isBlank()) {
                q.setQNo(String.valueOf(i + 1));
            }
        }
        return result;
    }

    /**
     * 处理碎片题：完全空的删掉，内容极短的并回上一题。
     *
     * 之前是「题干不足 4 字直接丢弃」，而框选区域小时很容易切出很短的题
     * （单独一行的选择项、判断题只剩半句），丢掉的正是题目内容本身。
     * 现在只删除真正没有内容的（如页码被误当成题号），有字的一律保留。
     */
    private void mergeFragments(List<ParsedQuestion> result) {
        for (int i = result.size() - 1; i >= 0; i--) {
            ParsedQuestion q = result.get(i);
            String stem = q.getStem() == null ? "" : q.getStem().trim();
            if (stem.isEmpty()) {
                // 没有任何正文（典型：孤立的页码/行号被当成题号）直接去掉
                result.remove(i);
                continue;
            }
            if (stem.length() >= 2 || i == 0) {
                continue;
            }
            // 极短且不是第一题：内容并回上一题，避免丢字
            ParsedQuestion prev = result.get(i - 1);
            prev.setStem((prev.getStem() == null ? "" : prev.getStem()) + "\n" + stem);
            if (prev.getAnswer() == null || prev.getAnswer().isBlank()) {
                prev.setAnswer(q.getAnswer());
            }
            result.remove(i);
        }
    }

    /**
     * 统一同一大题（题型大类）内所有小题的题型。
     *
     * 用户框选的区域通常就是一个大题，其下挂着 1、2、3 这样的小题，
     * 同一大题的题型应当一致；但单看题干时 OCR 噪声或个别特征容易判错，
     * 因此这里以大题标题为准回填，标题缺失时用该大题内的"多数票"。
     */
    private void unifySectionTypes(List<ParsedQuestion> result) {
        Map<Integer, List<ParsedQuestion>> groups = new LinkedHashMap<>();
        for (ParsedQuestion q : result) {
            groups.computeIfAbsent(q.getSectionOrder() == null ? 0 : q.getSectionOrder(),
                    k -> new ArrayList<>()).add(q);
        }
        for (List<ParsedQuestion> g : groups.values()) {
            // 组内题型超过 2 种，说明这个"大题"其实是几个大题被并到了一起
            // （典型：大题标题没识别出来，全卷都落在 section 1），
            // 此时按标题统一会把整卷题目刷成同一种题型，比不统一更糟
            long distinct = g.stream().map(ParsedQuestion::getQType).distinct().count();
            if (distinct >= 3) {
                continue;
            }
            String target = null;
            for (ParsedQuestion q : g) {
                if (q.getSectionTypeHint() != null) {
                    target = q.getSectionTypeHint();
                    break;
                }
            }
            if (target == null) {
                // 没有大题标题时退化为多数票，但要求 80% 以上一致：
                // 一旦某个大题标题漏识别，整组其实是好几个大题拼起来的，
                // 此时按多数票统一会把少数派的题型全部刷掉
                Map<String, Integer> cnt = new LinkedHashMap<>();
                for (ParsedQuestion q : g) {
                    cnt.merge(q.getQType(), 1, Integer::sum);
                }
                int best = 0;
                String bestType = null;
                for (Map.Entry<String, Integer> e : cnt.entrySet()) {
                    if (e.getValue() > best) {
                        best = e.getValue();
                        bestType = e.getKey();
                    }
                }
                if (bestType != null && best * 10 >= g.size() * 8) {
                    target = bestType;
                }
            }
            if (target == null) {
                continue;
            }
            for (ParsedQuestion q : g) {
                // 已解析出完整选项的题一定是选择题，不被大类覆盖
                if (q.getOptions() != null && q.getOptions().size() >= 2) {
                    continue;
                }
                q.setQType(target);
            }
        }
    }

    /** 收集阶段的题目原始数据（尚未 build） */
    private static class RawQuestion {
        final int sectionOrder;
        final String qNo;
        final String sectionType;
        final Double sectionScore;
        final String sectionNo;
        final String sectionTitle;
        final List<String> lines = new ArrayList<>();

        RawQuestion(int sectionOrder, String qNo, String sectionType, Double sectionScore,
                    String sectionNo, String sectionTitle) {
            this.sectionOrder = sectionOrder;
            this.qNo = qNo;
            this.sectionType = sectionType;
            this.sectionScore = sectionScore;
            this.sectionNo = sectionNo;
            this.sectionTitle = sectionTitle;
        }
    }

    /**
     * 判定一个"题号"是不是噪声（页脚页码、孤立行号等）。
     *
     * 只在证据很强时才丢：题号明显越界，或与上一题号差得离谱。
     * 早期版本还会因为"题号后面没有正文""题号回退"而丢弃，
     * 但框选区域小的时候，题号与题干分处两行、多区域各自从 1 开始编号都很常见，
     * 那些规则会把真正的题目吃掉，所以这里一律放行，交给后面的碎片合并处理。
     */
    private boolean isNoiseNumber(RawQuestion last, int sectionOrder, String no, String line) {
        int n = Integer.parseInt(no);
        // 正常试卷的小题号不会超过 60，超过的几乎都是页码/行号
        if (n > 60) {
            return true;
        }
        if (last != null && last.sectionOrder == sectionOrder && last.qNo != null
                && last.qNo.matches("\\d+")) {
            int prev = Integer.parseInt(last.qNo);
            // 跳号过大（如上一题 10 突然蹦到 88）才按页码处理；
            // 中间漏识别了几题导致的跳号不在此列，不能丢题
            if (n > prev && n - prev > 15) {
                return true;
            }
        }
        return false;
    }

    /** 行首题号匹配，按常见写法依次尝试 */
    private Matcher qStartMatcher(String line) {
        for (Pattern p : Q_STARTS) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                return m;
            }
        }
        return null;
    }

    /**
     * 把粘在题尾的行内大题标题切成独立行。
     *
     * 「热传导能力不同的材料，有着不同的用途。 三、正确答案我会选。(将正确答案…」
     * 会被切成「…有着不同的用途。」和「三、正确答案我会选。(将正确答案…」两行，
     * 后者才能被大题标题规则识别到。
     */
    private List<String> splitInlineSections(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            Matcher m = SECTION_INLINE.matcher(line);
            if (!m.find()) {
                out.add(line);
                continue;
            }
            int cut = m.start(1);
            String head = line.substring(0, cut).trim();
            if (!head.isEmpty()) {
                out.add(head);
            }
            out.add(line.substring(cut).trim());
        }
        return out;
    }

    /**
     * 把一行里的多个并排小题拆成多行（如「1. 甲  2. 乙  3. 丙」）。
     *
     * 只拆带点/顿号的题号，且要求同一行出现 2 个以上、并且序号大致递增，
     * 否则保持原样。判据收得比较紧是因为：小区域里 OCR 常把同一道题
     * （含多个小问、多个选项）识别成一行，过度拆分会让一道题变成好几道。
     */
    private List<String> expandInline(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            List<int[]> hits = new ArrayList<>(); // {位置, 题号}
            Matcher m = Q_START_INLINE.matcher(line);
            while (m.find()) {
                if (m.start() > 0) {
                    hits.add(new int[]{m.start(), Integer.parseInt(m.group(1))});
                }
            }
            if (hits.size() < 2 || !increasing(hits)) {
                out.add(line);
                continue;
            }
            int prev = 0;
            for (int[] h : hits) {
                String seg = line.substring(prev, h[0]).trim();
                if (!seg.isEmpty()) {
                    out.add(seg);
                }
                prev = h[0];
            }
            String last = line.substring(prev).trim();
            if (!last.isEmpty()) {
                out.add(last);
            }
        }
        return out;
    }

    /** 行内题号是否大致递增（允许少量重复/跳号，避免把 "2.5 元" 之类当题号） */
    private boolean increasing(List<int[]> hits) {
        for (int i = 1; i < hits.size(); i++) {
            int d = hits.get(i)[1] - hits.get(i - 1)[1];
            if (d < 0 || d > 3) {
                return false;
            }
        }
        return true;
    }

    /** 清洗文本行 */
    private List<String> cleanLines(String text) {
        // 全角数字/字母转半角；全角空格也转半角，
        // 否则 "三、正确答案我会选" 这类行内标题前面隔的是全角空格，正则匹配不到
        String norm = text.replace('\u3000', ' ')
                .replace('Ａ', 'A').replace('Ｂ', 'B').replace('Ｃ', 'C').replace('Ｄ', 'D')
                .replace('０', '0').replace('１', '1').replace('２', '2').replace('３', '3')
                .replace('４', '4').replace('５', '5').replace('６', '6').replace('７', '7')
                .replace('８', '8').replace('９', '9')
                .replace('（', '(').replace('）', ')');

        List<String> out = new ArrayList<>();
        for (String raw : norm.split("\\r?\\n")) {
            String t = raw.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (NOISE_PATTERN.matcher(t).matches()) {
                continue;
            }
            // 去掉纯符号行
            if (t.matches("^[-=_*·—\\s]{3,}$")) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    /** 匹配大题标题，返回 {中文序号, 标题, 题型 code}；不匹配返回 null */
    private SectionInfo matchSection(String line) {
        Matcher m = SECTION_PATTERN.matcher(line);
        String cnNo = null;
        String title = null;
        if (m.matches()) {
            cnNo = m.group(1);
            title = m.group(2);
        } else {
            Matcher m2 = SECTION_LOOSE.matcher(line);
            if (m2.matches()) {
                cnNo = m2.group(1);
                title = m2.group(2);
            }
        }
        String clean = cleanTitle(title);
        if (clean == null || classifyBySectionTitle(clean) == null) {
            // 整行匹配不到时，退一步按"序号 + 题型关键词"前缀识别：
            // 「三、正确答案我会选。(将正确答案的序号填在括号里)(18分)」这类
            // 带一长串括号说明的标题，整行正则往往匹配不上
            Matcher mp = SECTION_PREFIX.matcher(line);
            if (mp.find()) {
                String pTitle = cleanTitle(mp.group(2));
                String pType = pTitle == null ? null : classifyBySectionTitle(pTitle);
                if (pType != null) {
                    return new SectionInfo(mp.group(1), pTitle, pType);
                }
            }
        }
        if (clean == null) {
            return null;
        }
        return new SectionInfo(cnNo, clean, classifyBySectionTitle(clean));
    }

    /**
     * 收敛大题标题：去掉括号说明与尾部说明。
     *
     * 「填空题。（每空2分,共20分）」 -> 「填空题」
     * 「判断题。正确的在括号内打"V"，错误的打"×"」 -> 「判断题」
     * 只保留第一个标点之前的部分，说明文字（分值、答题要求）不应占满 UI。
     */
    private String cleanTitle(String t) {
        if (t == null) {
            return null;
        }
        String s = t.replaceAll("[（(].*$", "").trim();
        String head = s.split("[。．.、,，:：;；]")[0].trim();
        if (head.length() >= 2) {
            s = head;
        }
        s = s.replaceAll("[。．.,，:：;；、\\s]+$", "").trim();
        return s.isEmpty() ? null : s;
    }

    /** 大题标题解析结果 */
    private record SectionInfo(String cnNo, String title, String type) {
    }

    /**
     * 根据大题标题文字判定题型。
     *
     * 试卷里的大题标题很少直说"选择题/判断题"，多是
     * 「火眼金晴判正误」「正确答案我会选」「认真思考巧填空」这类花样说法，
     * 因此这里按"题型特征词"匹配，而不是只认同名关键词。
     * 顺序上把判断放在选择之前：判正误类标题里常夹带"正确"二字，容易被误当作选择。
     */
    private String classifyBySectionTitle(String title) {
        if (title == null) {
            return null;
        }
        if (containsAny(title, "判断", "正误", "对错", "真假", "判一判", "打√", "打×", "打“”")) {
            return QuestionType.JUDGE.name();
        }
        if (containsAny(title, "选择", "单项", "多项", "单选", "多选", "选出", "我会选",
                "选一选", "正确答案")) {
            return QuestionType.CHOICE.name();
        }
        if (containsAny(title, "填空", "填一填", "填写", "巧填空")) {
            return QuestionType.FILL.name();
        }
        if (containsAny(title, "计算", "算一算", "求值", "化简")) {
            return QuestionType.CALC.name();
        }
        if (containsAny(title, "作图", "画图", "绘图", "画一画", "描点")) {
            return QuestionType.DRAW.name();
        }
        if (containsAny(title, "实验", "探究", "观察", "测量")) {
            return QuestionType.EXPERIMENT.name();
        }
        if (containsAny(title, "解答", "应用", "简答", "综合", "阅读", "写作", "说一说", "解决问题")) {
            return QuestionType.ANSWER.name();
        }
        return null;
    }

    private boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** 从大题标题里提取每题分值：(每题2分，共20分) */
    private Double extractScore(String title) {
        Matcher m = Pattern.compile("每[小]?题\\s*(\\d+(?:\\.\\d+)?)\\s*分").matcher(title);
        if (m.find()) {
            return Double.parseDouble(m.group(1));
        }
        return null;
    }

    /**
     * 组装单道题：拆选项 / 答案 / 解析；判定题型、难度、知识点
     */
    private ParsedQuestion build(String no, int sectionOrder, String sectionNo, String sectionTitle,
                                 String sectionType, Double sectionScore, List<String> lines) {
        ParsedQuestion q = new ParsedQuestion();
        q.setQNo(no);
        q.setSectionOrder(sectionOrder);
        q.setSectionNo(sectionNo);
        q.setSectionTitle(sectionTitle);
        q.setSectionTypeHint(sectionType);

        StringBuilder stem = new StringBuilder();
        StringBuilder answer = new StringBuilder();
        StringBuilder analysis = new StringBuilder();
        List<String> optionLines = new ArrayList<>();

        int mode = 0; // 0=题干 1=答案 2=解析
        for (String line : lines) {
            Matcher am = ANSWER_PATTERN.matcher(line);
            Matcher dm = ANALYSIS_PATTERN.matcher(line);
            if (am.matches()) {
                mode = 1;
                if (!am.group(1).isBlank()) {
                    answer.append(am.group(1)).append(' ');
                }
                continue;
            }
            if (dm.matches()) {
                mode = 2;
                if (!dm.group(1).isBlank()) {
                    analysis.append(dm.group(1)).append(' ');
                }
                continue;
            }
            if (mode == 0) {
                stem.append(line).append('\n');
            } else if (mode == 1) {
                answer.append(line).append(' ');
            } else {
                analysis.append(line).append(' ');
            }
        }

        String stemText = stem.toString().trim();
        // 从题干里剥离选项
        String noOptions = extractOptions(stemText, q.getOptions());

        q.setAnswer(answer.toString().trim());
        q.setAnalysis(analysis.toString().trim());

        // 判定题型（用原始横线文本判定）
        q.setQType(detectType(sectionType, stemText, q.getOptions()));

        // 横线 / 空括号统一转成全角左右括号形式展现：____ -> （　　）
        String noOpt = BLANK_PATTERN.matcher(noOptions.trim()).replaceAll(BLANK_TEXT);
        q.setStem(BLANK_BRACKET.matcher(noOpt).replaceAll(BLANK_TEXT));

        // 分值
        q.setScore(sectionScore != null ? sectionScore : defaultScore(q.getQType()));

        // 难度与知识点
        q.setDifficulty(estimateDifficulty(q.getStem(), q.getQType()));
        q.setKnowledge(guessKnowledge(q.getStem()));

        return q;
    }

    /**
     * 从题干文本里提取选项
     * 支持：A. xxx B. xxx C. xxx D. xxx 在同一行或分行
     */
    private String extractOptions(String text, List<Map<String, String>> out) {
        // 找到第一个 "A." 或 "A、" 的位置（要求前面不是字母）
        Matcher first = Pattern.compile("(?<![A-Za-z])A\\s*[.．、:：]").matcher(text);
        if (!first.find()) {
            return text;
        }
        String stemPart = text.substring(0, first.start()).trim();
        String optPart = text.substring(first.start());

        Matcher m = OPTION_PATTERN.matcher(optPart);
        while (m.find()) {
            String key = m.group(1);
            String content = m.group(2).trim();
            if (content.isEmpty()) {
                continue;
            }
            Map<String, String> opt = new LinkedHashMap<>();
            opt.put("key", key);
            opt.put("content", content);
            out.add(opt);
        }
        // 至少要有 2 个选项才算解析成功
        if (out.size() < 2) {
            out.clear();
            return text;
        }
        return stemPart;
    }

    /** 填空位横线：下划线、全角下划线、连续破折号、连续减号 */
    private static final Pattern BLANK_PATTERN = Pattern.compile("[_＿]{2,}|[—–－]{2,}|[-]{3,}");

    /** 空括号（OCR 侧按横线位置补回的填空位，可能被清洗规则转成半角） */
    private static final Pattern BLANK_BRACKET = Pattern.compile("[（(][\\s　]*[）)]");

    private static final String BLANK_TEXT = "（　　）";

    /**
     * 题型判定：有选项 -> 选择；题干带横线空格 -> 填空（优先于大题上下文）；
     * 其次大题上下文，最后靠题干特征
     */
    private String detectType(String sectionType, String stem, List<Map<String, String>> options) {
        // 有完整选项 -> 一定是选择题
        if (options.size() >= 2) {
            return QuestionType.CHOICE.name();
        }
        // 大题已标明判断题：判断题末尾的打勾括号不能被"有空位=填空"误判成填空题
        if (QuestionType.JUDGE.name().equals(sectionType)) {
            return QuestionType.JUDGE.name();
        }
        // 题干带横线空格或填空括号 -> 填空题（所有带空位的一律按填空题）
        if (stem != null
                && (BLANK_PATTERN.matcher(stem).find() || BLANK_BRACKET.matcher(stem).find())) {
            return QuestionType.FILL.name();
        }
        if (sectionType != null) {
            return sectionType;
        }
        if (stem == null) {
            return QuestionType.OTHER.name();
        }
        // 判断
        if (Pattern.compile("(判断|对的|错的|是否正确|√|×)").matcher(stem).find()
                && stem.length() < 80) {
            return QuestionType.JUDGE.name();
        }
        // 作图
        if (Pattern.compile("(画出|作图|绘制|在图中|连接电路|标出)").matcher(stem).find()) {
            return QuestionType.DRAW.name();
        }
        // 实验
        if (Pattern.compile("(实验|探究|观察|测量|器材|装置)").matcher(stem).find()
                && Pattern.compile("(为什么|如何|设计|步骤|结论|现象|原因)").matcher(stem).find()) {
            return QuestionType.EXPERIMENT.name();
        }
        // 计算
        if (Pattern.compile("(计算|求|解方程|化简|求证|多少|几[个何只本支千]|等于)").matcher(stem).find()
                && stem.length() < 150) {
            return QuestionType.CALC.name();
        }
        // 解答
        if (Pattern.compile("(说明|解释|分析|简述|为什么|原因|谈谈|写出|回答)").matcher(stem).find()) {
            return QuestionType.ANSWER.name();
        }
        return QuestionType.OTHER.name();
    }

    private Double defaultScore(String type) {
        return switch (QuestionType.of(type)) {
            case CHOICE -> 3.0;
            case FILL -> 2.0;
            case JUDGE -> 2.0;
            case CALC -> 6.0;
            case ANSWER -> 8.0;
            case DRAW -> 4.0;
            case EXPERIMENT -> 6.0;
            default -> 5.0;
        };
    }

    /** 难度估算：字数 / 提问层级 / 题型 综合 */
    private Integer estimateDifficulty(String stem, String type) {
        int base = switch (QuestionType.of(type)) {
            case CHOICE, JUDGE -> 2;
            case FILL -> 2;
            case CALC -> 4;
            case DRAW -> 3;
            case EXPERIMENT, ANSWER -> 4;
            default -> 3;
        };
        int len = stem == null ? 0 : stem.length();
        if (len > 120) {
            base += 1;
        }
        if (len > 260) {
            base += 1;
        }
        if (Pattern.compile("(综合|结合|探究|判断.*是否正确|证明)").matcher(stem == null ? "" : stem).find()) {
            base += 1;
        }
        return Math.max(1, Math.min(5, base));
    }

    private static final Map<String, String[]> KNOWLEDGE_HINTS = new LinkedHashMap<>();

    static {
        KNOWLEDGE_HINTS.put("数与代数", new String[]{"整数", "小数", "分数", "百分数", "加减", "乘除", "方程", "比例", "运算"});
        KNOWLEDGE_HINTS.put("图形与几何", new String[]{"三角形", "长方形", "正方形", "圆", "面积", "周长", "体积", "角度", "平行", "对称"});
        KNOWLEDGE_HINTS.put("统计与概率", new String[]{"统计", "条形图", "折线图", "扇形图", "平均数", "概率", "可能"});
        KNOWLEDGE_HINTS.put("应用题", new String[]{"一共", "还剩", "速度", "单价", "相遇", "工程", "利润"});
        KNOWLEDGE_HINTS.put("力与运动", new String[]{"力", "运动", "速度", "摩擦", "重力", "压强"});
        KNOWLEDGE_HINTS.put("电与磁", new String[]{"电流", "电压", "电阻", "电路", "磁", "电磁"});
        KNOWLEDGE_HINTS.put("物质性质", new String[]{"物质", "密度", "质量", "熔化", "蒸发", "溶液"});
        KNOWLEDGE_HINTS.put("生命科学", new String[]{"植物", "动物", "细胞", "呼吸", "光合", "生态"});
    }

    private String guessKnowledge(String stem) {
        if (stem == null) {
            return "";
        }
        List<String> hit = new ArrayList<>();
        for (Map.Entry<String, String[]> e : KNOWLEDGE_HINTS.entrySet()) {
            for (String kw : e.getValue()) {
                if (stem.contains(kw)) {
                    hit.add(e.getKey());
                    break;
                }
            }
        }
        return hit.isEmpty() ? "" : String.join(",", hit.subList(0, Math.min(2, hit.size())));
    }
}
