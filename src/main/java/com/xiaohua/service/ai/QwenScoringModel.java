package com.xiaohua.service.ai;

import cn.hutool.http.HttpRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope 重排序（rerank）模型：实现 LangChain4j 的 {@link ScoringModel}，
 * 底层调用通义千问的 gte-rerank 接口。
 *
 * <p>rerank 与「向量检索」的区别：向量检索是「查询和文档各自算向量、比距离」（双塔），
 * rerank 是「把查询和文档放一起喂给模型打分」（交叉编码），后者更准、但更慢，
 * 所以典型做法是「向量先召回一批候选 → rerank 精排取前几个」。</p>
 */
@Component
@Slf4j
public class QwenScoringModel implements ScoringModel {

    @Value("${langchain4j.community.dashscope.chat-model.api-key}")
    private String apiKey;

    @Value("${rag.rerank.model:gte-rerank-v2}")
    private String modelName;

    @Value("${rag.rerank.base-url:https://dashscope.aliyuncs.com/api/v1}")
    private String baseUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 给「一批文档 + 一个查询」逐个打相关性分数，返回的分数与入参 segments **顺序一一对应**。
     */
    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        if (segments == null || segments.isEmpty()) {
            return Response.from(List.of());
        }
        List<String> documents = segments.stream().map(TextSegment::text).toList();

        Map<String, Object> input = new HashMap<>();
        input.put("query", query);
        input.put("documents", documents);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("return_documents", false);   // 只要分数，不返回文档原文，省流量
        Map<String, Object> body = new HashMap<>();
        body.put("model", modelName);
        body.put("input", input);
        body.put("parameters", parameters);

        String responseBody;
        try {
            responseBody = HttpRequest.post(baseUrl + "/services/rerank/text-rerank/text-rerank")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(body))
                    .timeout(10000)
                    .execute()
                    .body();
        } catch (Exception e) {
            throw new RuntimeException("DashScope rerank 调用失败: " + e.getMessage(), e);
        }

        return Response.from(parseScores(responseBody, segments.size()));
    }

    /**
     * 解析响应。接口返回的 results 是按分数降序且带原始下标 index 的，
     * 这里按下标还原成「和入参同序」的分数数组（缺的记 0 分）。
     */
    private List<Double> parseScores(String responseBody, int size) {
        double[] scores = new double[size];
        try {
            JsonNode results = objectMapper.readTree(responseBody).path("output").path("results");
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index >= 0 && index < size) {
                    scores[index] = item.path("relevance_score").asDouble(0.0);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("DashScope rerank 响应解析失败: " + e.getMessage(), e);
        }
        List<Double> list = new ArrayList<>(size);
        for (double s : scores) {
            list.add(s);
        }
        return list;
    }
}
