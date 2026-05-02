package org.a8043.simpleIDE.project.index;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONSupport;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

@Getter
@AllArgsConstructor
@ToString(exclude = {"parent", "module", "index"})
public class Package extends JSONSupport {
    private final Module module;
    private final Package parent;
    private final String name;
    private final Index index;

    public Package(Module module, Index index) {
        this(module, null, null, index);
    }

    public String getFullName() {
        return ArrayUtil.join(getPath(), ".");
    }

    public String[] getPath() {
        if (parent == null || parent.isRoot()) {
            return null;
        } else {
            String[] parentPath = parent.getPath();
            return ArrayUtil.addAll(parentPath != null ? parentPath : new String[]{}, new String[]{name});
        }
    }

    public boolean isRoot() {
        return name == null && parent == null && module == null;
    }

    public List<IndexPoint> getPoints() {
        return index.getIndexList().stream().filter(index -> index.getPkg() == this).toList();
    }

    public IndexPoint getPoint(String name) {
        return getPoints().stream().filter(point -> point.getName().equals(name)).findFirst().orElse(null);
    }

    @Override
    public JSONObject toJSON() {
        return new JSONObject().set("name", name).set("parent", parent == null ? null : parent.getFullName());
    }
}
