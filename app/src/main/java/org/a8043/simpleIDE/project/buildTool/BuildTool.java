package org.a8043.simpleIDE.project.buildTool;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Value;
import org.a8043.simpleIDE.project.ProjectEditor;

import java.io.File;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public abstract class BuildTool {
    @Getter(AccessLevel.PROTECTED)
    ProjectEditor editor;

    protected BuildTool(ProjectEditor editor) {
        this.editor = editor;
    }

    private List<Dependency> dependencyListCache;

    public abstract List<Dependency> doGetDependencyList();

    public final List<Dependency> getDependencyList() {
        return dependencyListCache != null ? dependencyListCache : (dependencyListCache = doGetDependencyList());
    }

    public final List<File> getDependencyJars() {
        return getDependencyList().stream().map(Dependency::getJarFile).toList();
    }

    public abstract Future<Integer> compile(Consumer<String> onOutput);

    private List<ModuleRecord> moduleListCache;

    protected abstract List<ModuleRecord> doGetModuleList();

    public final List<ModuleRecord> getModuleList() {
        return moduleListCache != null ? moduleListCache : (moduleListCache = doGetModuleList());
    }

    @Value
    public static class ModuleRecord {
        String name;
        File dir;
        Object extraInfo;

        public enum Type {
            NORMAL, UNNAMED
        }
    }
}
