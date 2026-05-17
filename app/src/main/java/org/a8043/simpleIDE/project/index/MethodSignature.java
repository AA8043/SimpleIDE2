package org.a8043.simpleIDE.project.index;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONSupport;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.stream.Collectors;

@AllArgsConstructor
@Getter
@Setter
public class MethodSignature extends JSONSupport {
    private String name;
    private Access access;
    private boolean isStatic;
    private IndexPoint returnType;
    private Map<String, IndexPoint> parameterMap;

    @Override
    public JSONObject toJSON() {
        return new JSONObject().set("name", name).set("access", access).set("isStatic", isStatic)
            .set("returnType", returnType != null ? new JSONObject()
                .set("path", StrUtil.join(".", (Object[]) returnType.getPath()))
                .set("moduleName", returnType.getPkg().getModule().getCacheName()) : new JSONObject())
            .set("parameterMap", parameterMap.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                entry -> entry.getValue() != null ? new JSONObject()
                    .set("path", StrUtil.join(".", (Object[]) entry.getValue().getPath()))
                    .set("moduleName", entry.getValue().getPkg().getModule().getCacheName()) :
                    new JSONObject().set("path", "void").set("moduleName", "<basic>"))));
    }
}
