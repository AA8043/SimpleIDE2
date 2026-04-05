package org.a8043.simpleIDE.project.index;

import cn.hutool.core.util.ArrayUtil;
import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Getter
@ToString(exclude = "fieldList")
public class IndexPoint {
    private final String name;
    private final Package pkg;
    private final IndexPoint parent;
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
}
