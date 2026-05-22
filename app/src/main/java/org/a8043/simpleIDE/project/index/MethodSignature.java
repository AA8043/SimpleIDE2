package org.a8043.simpleIDE.project.index;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONSupport;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private List<IndexPoint> parameterTypeList;

    public List<IndexPoint> getParameterTypeList() {
        if (parameterTypeList == null) {
            parameterTypeList = parameterMap == null ? new ArrayList<>() : new ArrayList<>(parameterMap.values());
        }
        return parameterTypeList;
    }

    public int getParameterCount() {
        if (parameterTypeList != null) {
            return parameterTypeList.size();
        }
        return parameterMap != null ? parameterMap.size() : 0;
    }

    @Override
    public JSONObject toJSON() {
        return new JSONObject().set("name", name).set("access", access).set("isStatic", isStatic)
            .set("returnType", toTypeJson(returnType))
            .set("parameterMap", parameterMap.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                entry -> toTypeJson(entry.getValue()), (left, right) -> right, LinkedHashMap::new)))
            .set("parameterTypeList", getParameterTypeList().stream().map(this::toTypeJson).toList());
    }

    private JSONObject toTypeJson(IndexPoint type) {
        return type != null ? new JSONObject()
            .set("path", StrUtil.join(".", (Object[]) type.getPath()))
            .set("moduleName", type.getPkg().getModule().getCacheName()) :
            new JSONObject().set("path", "void").set("moduleName", "<basic>");
    }
}
