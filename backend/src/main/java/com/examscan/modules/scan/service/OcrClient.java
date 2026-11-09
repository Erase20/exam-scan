package com.examscan.modules.scan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OCR 客户端（可插拔）
 *
 * engine=local  -> 调用本地 Python FastAPI OCR 服务
 * engine=mock   -> 内置演示数据，方便无 OCR 环境下体验全流程
 * engine=cloud  -> 预留云 API 接入点
 */
@Slf4j
@Service
public class OcrClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${exam-scan.ocr.engine:local}")
    private String engine;

    @Value("${exam-scan.ocr.base-url:http://127.0.0.1:8801}")
    private String baseUrl;

    @Value("${exam-scan.ocr.timeout-seconds:300}")
    private int timeout;

    public boolean isAvailable() {
        if ("mock".equalsIgnoreCase(engine)) {
            return true;
        }
        try {
            String resp = restTemplate.getForObject(baseUrl + "/health", String.class);
            return resp != null && resp.contains("\"status\"");
        } catch (Exception e) {
            log.warn("OCR 服务不可用: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 识别单个页面
     *
     * @return {width, height, skewAngle, blocks:[{blockType,x,y,w,h,content,confidence,imageBase64,lines}]}
     */
    public Map<String, Object> parsePage(byte[] imageBytes, String filename) {
        if ("mock".equalsIgnoreCase(engine)) {
            return MockOcrData.page();
        }
        String url = baseUrl + "/ocr/page";
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return filename == null ? "page.png" : filename;
            }
        });
        form.add("returnCrop", "true");
        return postForm(url, form);
    }

    /**
     * 识别整份文档（多页）
     */
    public Map<String, Object> parseDocument(List<byte[]> images, List<String> filenames) {
        if ("mock".equalsIgnoreCase(engine)) {
            return MockOcrData.document();
        }
        String url = baseUrl + "/ocr/document";
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        for (int i = 0; i < images.size(); i++) {
            final String fn = filenames.get(i);
            form.add("files", new ByteArrayResource(images.get(i)) {
                @Override
                public String getFilename() {
                    return fn;
                }
            });
        }
        form.add("returnCrop", "true");
        return postForm(url, form);
    }

    /**
     * 按指定区域识别（前端调整切分线 / 框选范围后的重新识别）
     *
     * @param regions 区域列表，元素含 x/y/w/h（原图像素坐标）
     * @return {width, height, regions:[{index,x,y,w,h,content,confidence,lines}]}
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> recognizeRegions(byte[] imageBytes, String filename, List<Map<String, Object>> regions) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        if ("mock".equalsIgnoreCase(engine)) {
            Map<String, Object> mockPage = MockOcrData.page();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mockBlocks = (List<Map<String, Object>>) mockPage.getOrDefault("blocks", List.of());
            for (int i = 0; i < regions.size(); i++) {
                Map<String, Object> r = new LinkedHashMap<>(regions.get(i));
                r.put("index", i);
                r.put("content", mockBlocks.isEmpty() ? "" : strOf(mockBlocks.get(i % mockBlocks.size()).get("content")));
                r.put("confidence", 0.9);
                list.add(r);
            }
            out.put("width", mockPage.get("width"));
            out.put("height", mockPage.get("height"));
            out.put("regions", list);
            return out;
        }
        String url = baseUrl + "/ocr/regions";
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return filename == null ? "page.png" : filename;
            }
        });
        try {
            form.add("regions", mapper.writeValueAsString(regions));
        } catch (Exception e) {
            throw new IllegalStateException("区域参数序列化失败", e);
        }
        Map<String, Object> resp = postForm(url, form);
        Object rs = resp.get("regions");
        if (rs == null) {
            resp.put("regions", list);
        }
        return resp;
    }

    /**
     * 纯文字识别（答题卡小区域）
     */
    public List<String> recognizeText(byte[] imageBytes) {
        if ("mock".equalsIgnoreCase(engine)) {
            return List.of("20240108", "张三");
        }
        String url = baseUrl + "/ocr/recognize";
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return "region.png";
            }
        });
        Map<String, Object> resp = postForm(url, form);
        List<String> out = new ArrayList<>();
        Object text = resp.get("text");
        if (text != null) {
            out.add(text.toString());
        }
        return out;
    }

    /** 高精度识别单题作答区（放大+增强重识别），按行返回文本，用于人工打分参考 */
    public String recognizeTextBest(byte[] imageBytes) {
        if ("mock".equalsIgnoreCase(engine)) {
            return "示例作答";
        }
        String url = baseUrl + "/ocr/recognize_best";
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return "cell.png";
            }
        });
        try {
            Map<String, Object> resp = postForm(url, form);
            Object text = resp.get("text");
            return text == null ? "" : text.toString();
        } catch (Exception e) {
            log.warn("作答区 OCR 失败: {}", e.getMessage());
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postForm(String url, MultiValueMap<String, Object> form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> req = new HttpEntity<>(form, headers);
        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(url, req, String.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                return mapper.readValue(resp.getBody(), LinkedHashMap.class);
            }
            throw new IllegalStateException("OCR 服务返回异常: " + resp.getStatusCode());
        } catch (Exception e) {
            log.error("调用 OCR 服务失败", e);
            throw new IllegalStateException("调用 OCR 服务失败，请确认 OCR 服务已启动: " + e.getMessage());
        }
    }

    public JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败", e);
        }
    }

    private static String strOf(Object o) {
        return o == null ? "" : o.toString();
    }
}
