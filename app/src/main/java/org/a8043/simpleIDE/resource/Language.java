package org.a8043.simpleIDE.resource;

import cn.hutool.json.JSONObject;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class Language {
    @Getter
    private final String name;
    private final String jsonContent;
    @Getter
    private String descriptionName;
    @Getter(AccessLevel.PACKAGE)
    private final Map<String, String> textMap = new HashMap<>();

    public Language(String name, String jsonContent) {
        this.name = name;
        this.jsonContent = jsonContent;
    }

    public String getText(String key, Map<String, Object> args) {
        AtomicReference<String> result = new AtomicReference<>(textMap.getOrDefault(key, key));
        args.forEach((k, v) -> result.set(result.get().replace("{" + k + "}", v.toString())));
        return result.get();
    }

    @SneakyThrows
    void init() {
        @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
        JSONObject json = new JSONObject(jsonContent);
        descriptionName = json.getStr("name");
        json.getJSONObject("texts").forEach((key, value) -> textMap.put(key, (String) value));
    }
}
