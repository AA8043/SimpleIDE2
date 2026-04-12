package org.a8043.simpleIDE.project.buildTool;

import org.a8043.simpleIDE.project.Jdk;
import org.a8043.simpleIDE.project.Project;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.project.ProjectModel;

import java.io.File;
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
    public ProjectModel sync(ProjectEditor editor) {
        return null;
    }

    @Override
    public Future<Integer> compile(Consumer<String> onOutput) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        return future;
    }

    @Override
    public List<ModuleRecord> getModuleList() {
        return List.of();
    }

    public static class GradleType extends BuildToolType {
        @Override
        public String name() {
            return "GRADLE";
        }

        @Override
        public void generateBuildScript(Project project, Jdk jdk, String groupId, String artifactId) {
        }

        @Override
        public BuildTool newBuildTool(ProjectEditor editor) {
            return new Gradle(editor);
        }

        @Override
        public boolean isUseThis(Project project) {
            return new File(project.getProjectDir(), "build.gradle").exists() ||
                   new File(project.getProjectDir(), "build.gradle.kts").exists() ||
                   new File(project.getProjectDir(), "settings.gradle").exists() ||
                   new File(project.getProjectDir(), "settings.gradle.kts").exists();
        }
    }
}
