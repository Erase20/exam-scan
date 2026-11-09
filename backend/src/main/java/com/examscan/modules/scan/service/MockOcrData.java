package com.examscan.modules.scan.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mock OCR 数据：用于演示模式 / 无 OCR 环境时跑通全流程
 * 模拟一份"五年级数学期末试卷"的真实 OCR 输出
 */
public final class MockOcrData {

    private MockOcrData() {
    }

    private static final String[] PAGE1_LINES = {
            "五年级数学期末检测卷",
            "（时间：90分钟  满分：100分）",
            "班级：__________  姓名：__________  学号：__________",
            "一、选择题（每题2分，共20分）",
            "1. 下面各数中，最接近1的是（  ）。",
            "A. 0.9    B. 1.05    C. 0.98    D. 1.2",
            "2. 一个长方形的长是8厘米，宽是5厘米，它的周长是（  ）厘米。",
            "A. 13    B. 26    C. 40    D. 80",
            "3. 把3米长的绳子平均分成5段，每段是全长的（  ）。",
            "A. 3/5    B. 1/5    C. 1/3    D. 5/3",
            "4. 下面图形中，是轴对称图形的是（  ）。",
            "A. 平行四边形    B. 等腰三角形    C. 任意梯形    D. 一般四边形",
            "5. 小明抛一枚硬币，连续三次都是正面朝上，第四次正面朝上的可能性是（  ）。",
            "A. 1/2    B. 1/4    C. 3/4    D. 1",
            "二、填空题（每题2分，共16分）",
            "6. 3.25读作__________，它是由3个一、2个0.1和5个__________组成的。",
            "7. 在○里填上“>”“<”或“=”：0.75○0.8      1.2○1.20",
            "8. 一个三角形的两个内角分别是45°和60°，第三个内角是__________度，这是__________三角形。",
            "9. 甲数是24，乙数是甲数的3/4，乙数是__________。",
            "10. 一个正方体的棱长是4分米，它的表面积是__________平方分米，体积是__________立方分米。",
    };

    private static final String[] PAGE2_LINES = {
            "三、判断题（每题2分，共10分）",
            "11. 两条直线不相交就一定平行。（  ）",
            "12. 所有的偶数都是合数。（  ）",
            "13. 1千克的铁比1千克的棉花重。（  ）",
            "14. 圆的周长是它的直径的π倍。（  ）",
            "15. 一个数除以小数，商一定比这个数大。（  ）",
            "四、计算题（每题5分，共20分）",
            "16. 直接写出得数：0.25×4=      3.6÷0.9=      1-0.35=      2/5+3/10=",
            "17. 解方程：2x+3.5=11.5",
            "18. 计算下面各题，能简算的要简算：2.5×3.2×1.25",
            "五、作图题（每题4分，共8分）",
            "19. 在方格纸上画一个面积是12平方厘米的长方形（每个小方格边长1厘米），并画出它的一条对称轴。",
            "20. 用量角器画一个75°的角，并标出度数。",
            "六、解答题（每题6分，共26分）",
            "21. 水果店运来一批苹果，第一天卖出总数的1/4，第二天卖出总数的3/8，还剩90千克。这批苹果一共有多少千克？",
            "22. 一辆汽车从甲地开往乙地，前2小时每小时行驶60千米，后3小时每小时行驶70千米。这辆汽车全程的平均速度是多少？",
            "23. 一个长方体游泳池，长50米，宽25米，深2米。现在要在池底和四壁抹一层水泥，抹水泥的面积是多少平方米？如果每平方米用水泥5千克，一共需要水泥多少千克？",
            "24. 下面是五年级一班同学最喜欢的课外活动统计表，请根据数据回答：跳绳18人，踢球12人，画画10人，下棋8人。喜欢跳绳的人数占全班人数的几分之几？（全班48人）",
    };

    /** 生成单页 mock 结构 */
    public static Map<String, Object> page() {
        return buildPage(1, PAGE1_LINES, 1240, 1754);
    }

    /** 生成整份 mock 文档 */
    public static Map<String, Object> document() {
        List<Map<String, Object>> pages = new ArrayList<>();
        pages.add(buildPage(1, PAGE1_LINES, 1240, 1754));
        pages.add(buildPage(2, PAGE2_LINES, 1240, 1754));

        StringBuilder full = new StringBuilder();
        for (Map<String, Object> p : pages) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> blocks = (List<Map<String, Object>>) p.get("blocks");
            for (Map<String, Object> b : blocks) {
                if ("TEXT".equals(b.get("blockType"))) {
                    full.append(b.get("content")).append('\n');
                }
            }
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("pageCount", pages.size());
        doc.put("costMs", 1820);
        doc.put("pages", pages);
        doc.put("fullText", full.toString());
        return doc;
    }

    private static Map<String, Object> buildPage(int pageNo, String[] lines, int w, int h) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        // 按 3~6 行一组聚成文字区块，模拟版面分析结果
        int i = 0;
        int y = 90;
        int sort = 0;
        while (i < lines.length) {
            int group = Math.min(lines.length - i, i == 0 ? 3 : 5);
            StringBuilder content = new StringBuilder();
            List<Map<String, Object>> lineList = new ArrayList<>();
            int by = y;
            for (int k = 0; k < group; k++, i++) {
                String text = lines[i];
                content.append(text).append('\n');
                Map<String, Object> ln = new LinkedHashMap<>();
                ln.put("text", text);
                ln.put("score", 0.95);
                ln.put("absY1", by);
                ln.put("absY2", by + 28);
                ln.put("absX", 100);
                ln.put("absX2", 1140);
                ln.put("cy", by + 14);
                ln.put("cx", 620);
                lineList.add(ln);
                by += 36;
            }
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("x", 90);
            b.put("y", y);
            b.put("w", 1060);
            b.put("h", by - y);
            b.put("blockType", "TEXT");
            b.put("confidence", 0.95);
            b.put("content", content.toString());
            b.put("sortNo", sort++);
            b.put("pageNo", pageNo);
            b.put("lines", lineList);
            blocks.add(b);
            y = by + 24;
        }

        // 插入一个图片区块（模拟几何示意图）
        Map<String, Object> img = new LinkedHashMap<>();
        img.put("x", 120);
        img.put("y", 300);
        img.put("w", 420);
        img.put("h", 320);
        img.put("blockType", "IMAGE");
        img.put("confidence", 0.88);
        img.put("content", "");
        img.put("sortNo", 2);
        img.put("pageNo", pageNo);
        img.put("imageBase64", "");
        blocks.add(2, img);

        Map<String, Object> page = new LinkedHashMap<>();
        page.put("pageNo", pageNo);
        page.put("width", w);
        page.put("height", h);
        page.put("skewAngle", 0.4);
        page.put("blocks", blocks);
        page.put("imageBase64", "");
        return page;
    }
}
