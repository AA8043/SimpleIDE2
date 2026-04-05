package org.a8043.simpleIDE.project.index;

import cn.hutool.core.util.ArrayUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

@Getter
@AllArgsConstructor
@ToString(exclude = {"parent", "module", "index"})
public class Package {
    private final Module module;
    private final Package parent;
    private final String name;
    private final Index index;

    public Package(Index index) {
        this(null, null, null, index);
    }

    public String getFullName() {
        return ArrayUtil.join(getPath(), ".");
    }

    public String[] getPath() {
        if (parent == null || parent.isRoot()) {
            return new String[]{name};
        } else {
            String[] parentPath = parent.getPath();
            String[] path = new String[parentPath.length + 1];
            System.arraycopy(parentPath, 0, path, 0, parentPath.length);
            path[parentPath.length] = name;
            return path;
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
}
