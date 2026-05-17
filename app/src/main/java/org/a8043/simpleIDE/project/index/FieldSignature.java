package org.a8043.simpleIDE.project.index;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONSupport;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@Getter
@Setter
@ToString
public class FieldSignature extends JSONSupport {
    private String name;
    private Access access;
    private boolean isStatic;
    private IndexPoint type;

    @Override
    public JSONObject toJSON() {
        return new JSONObject().set("name", name).set("access", access).set("isStatic", isStatic)
            .set("type", type != null ? new JSONObject()
                .set("path", StrUtil.join(".", (Object[]) type.getPath()))
                .set("moduleName", type.getPkg().getModule().getCacheName()) : new JSONObject());
    }
}
