package com.myagent.config;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 模型层:全走 OpenAI 兼容协议,厂商由配置决定。
 * 对齐 Phoenix DynamicModelFactory.java:39-69;热切换对齐 Phoenix AiModelRegistry 的双检锁+动态代理思路:
 * Agent 持有的是代理,每次调用委托到当前模型实例;已发出的请求继续使用原实例。
 */
@Configuration
@ConfigurationProperties(prefix = "myagent.model")
public class ModelFactory {

    private String baseUrl;
    private String apiKey;
    private String model;
    private Double temperature = 0.7;
    /** LLM_MOCK=true 或设置页开启:脚本化 Mock 模型,无 Key 跑通全链路(演示/联调用) */
    private Boolean mock = false;

    private volatile ChatModel current;
    private ChatModel proxy;

    @PostConstruct
    void init() {
        proxy = createProxy();
        current = build(baseUrl, apiKey, model, temperature, mock);
    }

    private ChatModel createProxy() {
        return (ChatModel) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ChatModel.class},
                (p, method, args) -> method.invoke(current, args));
    }

    /** 热切换:替换委托目标,新回合即用新模型。 */
    public synchronized void apply(String newBaseUrl, String newApiKey, String newModel,
                                   Double newTemperature, Boolean newMock) {
        apply(newBaseUrl, newApiKey, newModel, newTemperature, newMock, config -> {});
    }

    /** 先构建并持久化候选配置,全部成功后才发布;失败保留当前模型和配置。 */
    public synchronized void apply(String newBaseUrl, String newApiKey, String newModel,
                                   Double newTemperature, Boolean newMock,
                                   Consumer<Map<String, Object>> persist) {
        String nextBaseUrl = newBaseUrl == null || newBaseUrl.isBlank() ? baseUrl : newBaseUrl;
        String nextApiKey = newApiKey == null || newApiKey.isBlank() ? apiKey : newApiKey;
        String nextModel = newModel == null || newModel.isBlank() ? model : newModel;
        Double nextTemperature = newTemperature == null ? temperature : newTemperature;
        Boolean nextMock = newMock == null ? mock : newMock;
        ChatModel next = build(nextBaseUrl, nextApiKey, nextModel, nextTemperature, nextMock);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("baseUrl", nextBaseUrl);
        config.put("apiKey", nextApiKey);
        config.put("model", nextModel);
        config.put("temperature", nextTemperature);
        config.put("mock", nextMock);
        persist.accept(config);
        baseUrl = nextBaseUrl;
        apiKey = nextApiKey;
        model = nextModel;
        temperature = nextTemperature;
        mock = nextMock;
        current = next;
    }

    private ChatModel build(String baseUrl, String apiKey, String model, Double temperature, Boolean mock) {
        if (Boolean.TRUE.equals(mock)) {
            return new MockScriptedChatModel();
        }
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .build())
                .build();
    }

    @Bean
    public ChatModel chatModel() {
        return proxy;
    }

    // ---------- 配置读取(设置页展示) ----------

    public String getBaseUrl() { return baseUrl; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public Double getTemperature() { return temperature; }
    public Boolean getMock() { return mock; }

    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public void setModel(String model) { this.model = model; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public void setMock(Boolean mock) { this.mock = mock; }
}
