package org.a8043.simpleIDE.project.index;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONSupport;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.a8043.simpleIDE.project.ProjectModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

@EqualsAndHashCode(callSuper = false)
@ToString
public class Module extends JSONSupport {
    @Getter
    private final ProjectModule projectModule;
    @Getter
    private final List<Module> requireList;
    @Getter
    private final List<Package> packageList = new CopyOnWriteArrayList<>();
    private final Index index;

    private Module(ProjectModule projectModule, List<Module> requireList, Index index) {
        this.projectModule = projectModule;
        this.requireList = requireList;
        this.index = index;
        packageList.add(new Package(this, index));
    }

    public Module(Index index) {
        this(null, null, index);
    }

    public Module(ProjectModule projectModule, Index index) {
        this(projectModule, new ArrayList<>(), index);
    }

    void addRequire(Module module) {
        Objects.requireNonNull(requireList).add(module);
    }

    public Package getPackage(String[] path) {
        return packageList.stream().filter(pkg -> ArrayUtil.equals(pkg.getPath(), path)).findFirst().orElse(null);
    }

    synchronized Package getOrCreatePackage(String[] path) {
        Package lastPkg = packageList.getFirst();
        for (String name : path) {
            if (!hasPackage(lastPkg, name)) {
                packageList.add(lastPkg = new Package(this, lastPkg, name, index));
            } else {
                lastPkg = getPackage(lastPkg, name);
            }
        }
        return lastPkg;
    }

    public Package getPackage(Package parent, String name) {
        List<Package> dataList = new ArrayList<>(packageList);
        return dataList.stream().filter(pkg ->
            Objects.equals(name, pkg.getName()) && pkg.getParent() == parent).findFirst().orElse(null);
    }

    public boolean hasPackage(Package parent, String name) {
        return getPackage(parent, name) != null;
    }

    public IndexPoint getPoint(String[] path) {
        return index.getIndexList().stream().filter(point -> ArrayUtil.equals(point.getPath(), path))
            .findFirst().orElse(null);
    }

    public boolean hasPoint(String[] path) {
        if (path == null || path.length == 0) {
            return false;
        }
        return index.getIndexList().stream().anyMatch(point ->
            point.getPkg().getModule() == this && ArrayUtil.equals(point.getPath(), path));
    }

    public List<IndexPoint> getPoints() {
        return packageList.stream().map(Package::getPoints).flatMap(List::stream).toList();
    }

    public String getCacheName() {
        return projectModule != null ? projectModule.getName() :
            index.getModuleList().getFirst() == this ? "<unnamed>" :
            index.getModuleList().get(1) == this ? "<basic>" : null;
    }

    @Override
    public JSONObject toJSON() {
        return new JSONObject().set("name", getCacheName()).set("requireList", requireList != null ?
                requireList.stream().map(m -> m.getProjectModule().getName()).toList() : null)
            .set("packageList", packageList.stream().map(Package::getFullName).toList());
    }
}
