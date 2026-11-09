package com.examscan.modules.grading.service;

import lombok.extern.slf4j.Slf4j;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 答题卡透视校正（纯 Java 实现，无原生依赖）
 *
 * 扫描/拍照的答题卡常有旋转与梯形变形，按模板坐标直接取涂点会错位。
 * 做法：
 *  1. 在图像上找四个角的实心方块定位标记（连通域 + 面积/长宽比过滤）
 *  2. 求单应矩阵（4 点 -> 4 点，解 8 元线性方程组）
 *  3. 逆向映射每个目标像素，双线性插值采样，输出标准尺寸图像
 *
 * 找不到 4 个标记时退化为"等比缩放 + 居中"，保证流程不中断。
 */
@Slf4j
public class PerspectiveCorrector {

    /** 目标图中定位标记的中心坐标（与模板一致） */
    public static BufferedImage normalize(BufferedImage src, int pageW, int pageH, Map<?, ?> layout) {
        src = trimToSinglePage(src, pageW, pageH);
        try {
            double[][] tgt = targetMarkerCenters(layout, pageW, pageH);
            double[][] srcPts = findMarkerCenters(src);
            if (srcPts != null) {
                double[] h = solveHomography(srcPts, tgt);
                if (h != null) {
                    BufferedImage out = warp(src, pageW, pageH, h);
                    if (out != null) {
                        log.info("透视校正成功");
                        return out;
                    }
                }
            }
        } catch (Throwable e) {
            log.warn("透视校正异常，降级为等比缩放: {}", e.getMessage());
        }
        return scaleFit(src, pageW, pageH);
    }

    /**
     * 手机拍照自动裁切：只有找到四个定位标记才做透视校正并输出标准页面。
     * 与 normalize() 的区别是找不到点位时返回 null（让前端提示重拍），
     * 不会退化成「等比缩放」冒充自动裁切。
     */
    public static BufferedImage autoCorrect(BufferedImage src, int pageW, int pageH) {
        BufferedImage page = trimToSinglePage(src, pageW, pageH);
        double[][] pts = findMarkerCenters(page);
        if (pts == null) {
            log.info("自动裁切：未找到四角定位标记");
            return null;
        }
        double[][] tgt = targetMarkerCenters(Map.of(), pageW, pageH);
        double[] h = solveHomography(pts, tgt);
        return h == null ? null : warp(page, pageW, pageH, h);
    }

    /** 实时取景用：只返回 TL/TR/BL/BR 四点（原图像素坐标），未找到返回 null */
    public static double[][] detectMarkers(BufferedImage src, int pageW, int pageH) {
        return findMarkerCenters(trimToSinglePage(src, pageW, pageH));
    }

    /**
     * 多页拼接长图（整卷导出）只保留顶部一页，避免跨页定位标记导致整体错位。
     * 注意：竖拍单页照片（上下留背景）比例也会超限，不能只看比例——
     * 只有存在「整行连续纯白的分页缝隙」时才判定为多页并裁剪；
     * 裁掉的是页间空白，第一页内容与角标完整保留。
     */
    private static BufferedImage trimToSinglePage(BufferedImage src, int pageW, int pageH) {
        double srcRatio = src.getHeight() / (double) src.getWidth();
        double pageRatio = pageH / (double) pageW;
        if (srcRatio <= pageRatio * 1.25) {
            return src;
        }
        int w = src.getWidth(), h = src.getHeight();
        int step = Math.max(1, h / 800);
        int rows = h / step;
        int xStride = Math.max(1, w / 120);
        double[] white = new double[rows];
        for (int r = 0; r < rows; r++) {
            int y = r * step;
            int cnt = 0, total = 0;
            for (int x = 0; x < w; x += xStride) {
                int rgb = src.getRGB(x, y);
                int g = (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
                total++;
                if (g > 215) {
                    cnt++;
                }
            }
            white[r] = cnt / (double) total;
        }
        int need = Math.max(6, rows / 150);   // 连续纯白行数达到页高的一定量才算分页缝隙
        int run = 0, cut = -1;
        for (int r = 0; r < rows; r++) {
            if (white[r] > 0.96) {
                run++;
                if (run >= need) {
                    cut = (r - run + 1) * step;
                    break;
                }
            } else {
                run = 0;
            }
        }
        if (cut > h * 0.3 && cut < h * 0.92) {
            log.info("检测到多页拼接长图，裁剪至第一页: {} -> {}", h, cut);
            return src.getSubimage(0, 0, w, cut);
        }
        log.info("长图但无分页缝隙（竖拍单页照片），不做裁剪");
        return src;
    }

    private static double[][] targetMarkerCenters(Map<?, ?> layout, int pageW, int pageH) {
        List<?> markers = (List<?>) layout.get("markers");
        double[][] t = new double[4][2];
        if (markers != null && markers.size() >= 4) {
            for (int i = 0; i < 4; i++) {
                Map<?, ?> m = (Map<?, ?>) markers.get(i);
                t[i][0] = num(m.get("x"), 0) + num(m.get("size"), 60) / 2.0;
                t[i][1] = num(m.get("y"), 0) + num(m.get("size"), 60) / 2.0;
            }
        } else {
            t = new double[][]{{170, 170}, {pageW - 170, 170}, {170, pageH - 170}, {pageW - 170, pageH - 170}};
        }
        return t;
    }

    /**
     * 在源图中找四个定位方块中心，顺序固定为：左上、右上、左下、右下
     */
    private static double[][] findMarkerCenters(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        // 降采样加速（每 2 像素取 1）
        int step = Math.max(1, Math.min(w, h) / 1200);
        int sw = w / step, sh = h / step;
        boolean[] dark = new boolean[sw * sh];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int rgb = img.getRGB(x * step, y * step);
                int g = (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
                dark[y * sw + x] = g < 110;
            }
        }

        // 连通域标记（BFS）
        int[] labels = new int[sw * sh];
        List<int[]> boxes = new ArrayList<>(); // x,y,w,h,area
        int label = 0;
        int[] queue = new int[sw * sh];
        for (int i = 0; i < sw * sh; i++) {
            if (!dark[i] || labels[i] != 0) {
                continue;
            }
            label++;
            int qh = 0, qt = 0;
            queue[qt++] = i;
            labels[i] = label;
            int minX = sw, minY = sh, maxX = 0, maxY = 0, cnt = 0;
            while (qh < qt) {
                int cur = queue[qh++];
                int cx = cur % sw, cy = cur / sw;
                cnt++;
                if (cx < minX) minX = cx;
                if (cx > maxX) maxX = cx;
                if (cy < minY) minY = cy;
                if (cy > maxY) maxY = cy;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = cx + dx, ny = cy + dy;
                        if (nx < 0 || ny < 0 || nx >= sw || ny >= sh) {
                            continue;
                        }
                        int ni = ny * sw + nx;
                        if (dark[ni] && labels[ni] == 0) {
                            labels[ni] = label;
                            queue[qt++] = ni;
                        }
                    }
                }
            }
            boxes.add(new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1, cnt});
        }

        double imgArea = (double) sw * sh;
        List<double[]> cand = new ArrayList<>();
        for (int[] b : boxes) {
            double bw = b[2], bh = b[3];
            double area = bw * bh;
            double ratio = bw / Math.max(1, bh);
            double fill = b[4] / Math.max(1, area);
            // 实心方块：长宽比接近 1，填充率高，面积占比合理
            if (area / imgArea > 0.00008 && area / imgArea < 0.06
                    && ratio > 0.55 && ratio < 1.8 && fill > 0.72 && bw >= 10 && bh >= 10) {
                cand.add(new double[]{(b[0] + bw / 2.0) * step, (b[1] + bh / 2.0) * step});
            }
        }
        if (cand.size() < 4) {
            log.info("定位标记不足（{} 个）", cand.size());
            return null;
        }

        double[] tl = null, tr = null, bl = null, br = null;
        double sMin = Double.MAX_VALUE, sMax = -Double.MAX_VALUE;
        double dMin = Double.MAX_VALUE, dMax = -Double.MAX_VALUE;
        for (double[] p : cand) {
            double s = p[0] + p[1];
            double d = p[0] - p[1];
            if (s < sMin) {
                sMin = s;
                tl = p;
            }
            if (s > sMax) {
                sMax = s;
                br = p;
            }
            if (d > dMax) {
                dMax = d;
                tr = p;
            }
            if (d < dMin) {
                dMin = d;
                bl = p;
            }
        }
        if (tl == null || tr == null || bl == null || br == null
                || (tl == br) || (tr == bl)) {
            return null;
        }
        log.info("定位标记坐标: TL({},{}), TR({},{}), BL({},{}), BR({},{})",
                (int) tl[0], (int) tl[1], (int) tr[0], (int) tr[1],
                (int) bl[0], (int) bl[1], (int) br[0], (int) br[1]);
        return new double[][]{tl, tr, bl, br};
    }

    /**
     * 读取单页图顶部的机器可读页码标识，返回 0-based 页码；读取失败返回 -1。
     * 标识为二进制方块（见 AnswerSheetService.drawPageCode），位于每页顶部中央。
     */
    public static int detectPage(BufferedImage src, Map<?, ?> layout) {
        if (layout == null) {
            return -1;
        }
        Map<?, ?> pc = (Map<?, ?>) layout.get("pageCode");
        if (pc == null) {
            return -1;
        }
        int bits = ((Number) pc.get("bits")).intValue();
        int size = ((Number) pc.get("size")).intValue();
        int gap = ((Number) pc.get("gap")).intValue();
        int x0 = ((Number) pc.get("x")).intValue();
        int y0 = ((Number) pc.get("y")).intValue();
        int value = 0;
        int sampled = 0;
        for (int i = 0; i < bits; i++) {
            int cx = x0 + i * (size + gap) + size / 2;
            int cy = y0 + size / 2;
            if (cx < 0 || cy < 0 || cx >= src.getWidth() || cy >= src.getHeight()) {
                return -1;
            }
            int dark = 0, total = 0;
            for (int dx = -size / 4; dx <= size / 4; dx += 2) {
                for (int dy = -size / 4; dy <= size / 4; dy += 2) {
                    int xx = cx + dx, yy = cy + dy;
                    if (xx < 0 || yy < 0 || xx >= src.getWidth() || yy >= src.getHeight()) {
                        continue;
                    }
                    int rgb = src.getRGB(xx, yy) & 0xFFFFFF;
                    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                    if ((r + g + b) / 3 < 128) {
                        dark++;
                    }
                    total++;
                }
            }
            if (total == 0) {
                return -1;
            }
            boolean on = (dark * 1.0 / total) > 0.4;
            sampled++;
            value = (value << 1) | (on ? 1 : 0);
        }
        if (sampled != bits) {
            return -1;
        }
        return value - 1; // 转回 0-based 页码
    }

    /**
     * 求解单应矩阵 H，使 dst = H * src（齐次坐标）
     * 展开后得到 8 元线性方程组，用高斯消元求解
     */
    private static double[] solveHomography(double[][] src, double[][] dst) {
        double[][] a = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = src[i][0], y = src[i][1];
            double u = dst[i][0], v = dst[i][1];
            a[i * 2] = new double[]{x, y, 1, 0, 0, 0, -u * x, -u * y, u};
            a[i * 2 + 1] = new double[]{0, 0, 0, x, y, 1, -v * x, -v * y, v};
        }
        // 高斯消元
        for (int col = 0; col < 8; col++) {
            int piv = col;
            for (int r = col + 1; r < 8; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) {
                    piv = r;
                }
            }
            if (Math.abs(a[piv][col]) < 1e-10) {
                return null;
            }
            double[] tmp = a[col];
            a[col] = a[piv];
            a[piv] = tmp;
            double div = a[col][col];
            for (int c = col; c <= 8; c++) {
                a[col][c] /= div;
            }
            for (int r = 0; r < 8; r++) {
                if (r == col) {
                    continue;
                }
                double f = a[r][col];
                if (Math.abs(f) < 1e-12) {
                    continue;
                }
                for (int c = col; c <= 8; c++) {
                    a[r][c] -= f * a[col][c];
                }
            }
        }
        double[] h = new double[9];
        for (int i = 0; i < 8; i++) {
            h[i] = a[i][8];
        }
        h[8] = 1.0;
        return h;
    }

    /** 逆映射 + 双线性插值 */
    private static BufferedImage warp(BufferedImage src, int pageW, int pageH, double[] h) {
        try {
            double[] inv = invert3x3(new double[][]{
                    {h[0], h[1], h[2]},
                    {h[3], h[4], h[5]},
                    {h[6], h[7], h[8]}});
            if (inv == null) {
                return null;
            }
            BufferedImage out = new BufferedImage(pageW, pageH, BufferedImage.TYPE_INT_RGB);
            int sw = src.getWidth(), sh = src.getHeight();
            int[] rowRgb = new int[sw];
            int[] outRow = new int[pageW];
            for (int y = 0; y < pageH; y++) {
                for (int x = 0; x < pageW; x++) {
                    double denom = inv[6] * x + inv[7] * y + inv[8];
                    if (Math.abs(denom) < 1e-10) {
                        outRow[x] = 0xFFFFFF;
                        continue;
                    }
                    double sx = (inv[0] * x + inv[1] * y + inv[2]) / denom;
                    double sy = (inv[3] * x + inv[4] * y + inv[5]) / denom;
                    outRow[x] = sampleBilinear(src, sx, sy, rowRgb);
                }
                out.setRGB(0, y, pageW, 1, outRow, 0, pageW);
            }
            return out;
        } catch (Throwable e) {
            log.warn("图像变换失败: {}", e.getMessage());
            return null;
        }
    }

    private static int sampleBilinear(BufferedImage src, double sx, double sy, int[] tmp) {
        int sw = src.getWidth(), sh = src.getHeight();
        if (sx < 0 || sy < 0 || sx >= sw - 1 || sy >= sh - 1) {
            return 0xFFFFFF;
        }
        int x0 = (int) sx, y0 = (int) sy;
        double fx = sx - x0, fy = sy - y0;
        int p00 = src.getRGB(x0, y0);
        int p10 = src.getRGB(x0 + 1, y0);
        int p01 = src.getRGB(x0, y0 + 1);
        int p11 = src.getRGB(x0 + 1, y0 + 1);
        int r = (int) (chan(p00, 16) * (1 - fx) * (1 - fy) + chan(p10, 16) * fx * (1 - fy)
                + chan(p01, 16) * (1 - fx) * fy + chan(p11, 16) * fx * fy);
        int g = (int) (chan(p00, 8) * (1 - fx) * (1 - fy) + chan(p10, 8) * fx * (1 - fy)
                + chan(p01, 8) * (1 - fx) * fy + chan(p11, 8) * fx * fy);
        int b = (int) (chan(p00, 0) * (1 - fx) * (1 - fy) + chan(p10, 0) * fx * (1 - fy)
                + chan(p01, 0) * (1 - fx) * fy + chan(p11, 0) * fx * fy);
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int chan(int rgb, int shift) {
        return (rgb >> shift) & 0xFF;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static double[] invert3x3(double[][] m) {
        double det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        if (Math.abs(det) < 1e-12) {
            return null;
        }
        double invDet = 1.0 / det;
        double[] r = new double[9];
        r[0] = (m[1][1] * m[2][2] - m[1][2] * m[2][1]) * invDet;
        r[1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) * invDet;
        r[2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) * invDet;
        r[3] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) * invDet;
        r[4] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) * invDet;
        r[5] = (m[0][2] * m[1][0] - m[0][0] * m[1][2]) * invDet;
        r[6] = (m[1][0] * m[2][1] - m[1][1] * m[2][0]) * invDet;
        r[7] = (m[0][1] * m[2][0] - m[0][0] * m[2][1]) * invDet;
        r[8] = (m[0][0] * m[1][1] - m[0][1] * m[1][0]) * invDet;
        return r;
    }

    public static BufferedImage scaleFit(BufferedImage src, int pageW, int pageH) {
        BufferedImage out = new BufferedImage(pageW, pageH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, pageW, pageH);
        double scale = Math.min(pageW / (double) src.getWidth(), pageH / (double) src.getHeight());
        int w = (int) (src.getWidth() * scale);
        int h = (int) (src.getHeight() * scale);
        g.drawImage(src, (pageW - w) / 2, (pageH - h) / 2, w, h, null);
        g.dispose();
        return out;
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }
}
