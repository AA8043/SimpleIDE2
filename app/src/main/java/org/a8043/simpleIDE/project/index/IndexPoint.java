package org.a8043.simpleIDE.project.index;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONSupport;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Getter
@ToString(exclude = "fieldList")
@EqualsAndHashCode(callSuper = false)
public class IndexPoint extends JSONSupport {
    private final String name;
    private final Package pkg;
    @Setter(AccessLevel.PACKAGE)
    private IndexPoint parent;
    private final List<MethodSignature> methodList = new ArrayList<>();
    private final List<FieldSignature> fieldList = new ArrayList<>();

    public IndexPoint(String name, Package pkg, IndexPoint parent) {
        this.name = name;
        this.pkg = pkg;
        this.parent = parent;
    }

    public String[] getPath() {
        String[] nameArray = {name};
        if (isBasicType()) {
            return nameArray;
        }
        String[] pkgPath = pkg.getPath();
        if (pkgPath == null || ArrayUtil.equals(pkgPath, new Object[]{null})) {
            return nameArray;
        } else {
            return ArrayUtil.addAll(pkgPath, nameArray);
        }
    }

    public List<MethodSignature> getMethodList(String name) {
        return methodList.stream().filter(method -> method.getName().equals(name)).toList();
    }

    public FieldSignature getField(String name) {
        return fieldList.stream().filter(field -> field.getName().equals(name)).findFirst().orElse(null);
    }

    public boolean isBasicType() {
        return pkg == null && parent == null;
    }

    @Override
    public JSONObject toJSON() {
        return new JSONObject().set("path", ArrayUtil.join(getPath(), "."))
            .set("moduleName", pkg.getModule().getCacheName())
            .set("methodList", methodList).set("fieldList", fieldList);
    }
}
