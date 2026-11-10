package com.examscan.common;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 试卷「答案在相应位置」的通用工具（Word 导出与前端预览使用同一套规则）：
 *  - 把一条答案串按 空格 / 中英文逗号 / 中英文分号 / 换行 拆成逐空答案（不用「/」拆，避免误伤 3/4 这类分数答案）
 *  - 找题干中的空位：（　　）( ) ____ ＿＿＿ 等，按出现顺序逐个填入答案
 */
public final class PaperAnswerUtil {

    /** 空位：成对括号内只有空白/下划线，或 2 个以上连续下划线（中英文括号均支持） */
    private static final Pattern BLANK = Pattern.compile("[（(][\\s\u3000_＿]*[)）]|[_＿]{2,}");

    private PaperAnswerUtil() {
    }

    /** 拆分多空答案：空格 / 中英文逗号 / 中英文分号 / 换行 均可作为分隔符（如「7 5 5」「7，5，5」「7；5；5」-> [7, 5, 5]） */
    public static List<String> splitAnswers(String answer) {
        List<String> out = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            return out;
        }
        for (String p : answer.split("[\\s；;，,]+")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 题干中的空位数量 */
    public static int countBlanks(String stem) {
        if (stem == null) {
            return 0;
        }
        Matcher m = BLANK.matcher(stem);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /**
     * 把答案按顺序填入题干空位（答案用括号包裹，便于与原题区分）。
     * 答案少于空位时，多余空位保留为（　　）；答案多于空位时，多余部分放入 leftover。
     */
    public static FillResult fillBlanks(String stem, List<String> answers) {
        String s = stem == null ? "" : stem;
        List<String> ans = answers == null ? List.of() : answers;
        Matcher m = BLANK.matcher(s);
        StringBuffer sb = new StringBuffer();
        int i = 0;
        while (m.find()) {
            String rep = i < ans.size() ? "（" + ans.get(i++) + "）" : "（　　）";
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return new FillResult(sb.toString(), i < ans.size() ? ans.subList(i, ans.size()) : List.of());
    }

    /** 填空结果：filled = 填好的题干；leftover = 空位不够时剩余的答案段 */
    public record FillResult(String filled, List<String> leftover) {
    }
}
