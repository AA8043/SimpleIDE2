package org.a8043.simpleIDE.project.index;

import cn.hutool.core.util.ArrayUtil;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

@EqualsAndHashCode
@ToString
public class Module {
    @Getter
    private final String name;
    @Getter
    private final Location location;
    @Getter
    private final List<Module> requireList;
    @Getter
    private final List<Package> packageList = new CopyOnWriteArrayList<>();
    private final Index index;

    private Module(String name, Location location, List<Module> requireList, Index index) {
        this.name = name;
        this.location = location;
        this.requireList = requireList;
        this.index = index;
        packageList.add(new Package(index));
    }

    public Module(Index index) {
        this(null, null, null, index);
    }

    public Module(String name, Location location, Index index) {
        this(name, location, new ArrayList<>() {
            @Override
            public boolean add(Module module) {
                System.out.println("add require module " + module.getName() + " to " + name);
                return super.add(module);
            }
        }, index);
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
        return index.getIndexList().stream().filter(point ->
                point != null && point.getName().equals(path[path.length - 1]) &&
                point.getPkg() == getPackage(ArrayUtil.sub(path, 0, path.length - 1)))
            .findFirst().orElse(null);
    }

    public boolean hasPoint(String[] path) {
        if (path == null || path.length == 0) {
            return false;
        }
        Package pkg = getPackage(ArrayUtil.sub(path, 0, path.length - 1));
        return index.getIndexList().stream().anyMatch(point ->
            point.getPkg() == pkg && ArrayUtil.equals(point.getPath(), path));
    }

    public List<IndexPoint> getPoints() {
        return packageList.stream().map(Package::getPoints).flatMap(List::stream).toList();
    }

    public void load() {
    }

    public enum Location {
        PROJECT, JDK, DEPENDENCY
    }
}
