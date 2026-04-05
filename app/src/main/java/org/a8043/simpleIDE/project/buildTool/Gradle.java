package org.a8043.simpleIDE.project.buildTool;

import org.a8043.simpleIDE.project.ProjectEditor;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Consumer;

// TODO: gradle
public class Gradle extends BuildTool {
    public Gradle(ProjectEditor editor) {
        super(editor);
    }

    @Override
    public List<Dependency> doGetDependencyList() {
        return List.of();
    }

    @Override
    public Future<Integer> compile(Consumer<String> onOutput) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        return future;
    }

    @Override
    public List<ModuleRecord> doGetModuleList() {
        return List.of();
    }
}
