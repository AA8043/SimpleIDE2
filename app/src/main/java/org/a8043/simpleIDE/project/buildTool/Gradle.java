package org.a8043.simpleIDE.project.buildTool;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import org.a8043.simpleIDE.project.*;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.SourceDirectory;
import org.gradle.tooling.model.eclipse.ClasspathAttribute;
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.idea.IdeaProject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public class Gradle extends BuildTool {
    public Gradle(ProjectEditor editor) {
        super(editor);
    }

    @Override
    public ProjectModel sync(ProjectEditor editor) {
        List<Dependency> dependencyList = new ArrayList<>();
        List<ProjectModule> moduleList = new ArrayList<>();
        try (ProjectConnection connection = GradleConnector.newConnector()
            .forProjectDirectory(editor.getProject().getProjectDir()).connect()) {
            IdeaProject ideaProject = connection.getModel(IdeaProject.class);
            EclipseProject eclipseProject = connection.getModel(EclipseProject.class);

            eclipseProject.getClasspath().forEach(entry -> {
                if (entry instanceof EclipseExternalDependency externalDependency) {
                    DomainObjectSet<? extends ClasspathAttribute> classpathAttributes =
                        externalDependency.getClasspathAttributes();
                    String groupId = classpathAttributes.stream().filter(attr ->
                            "gradle.module.group".equals(attr.getName())).findFirst()
                        .map(ClasspathAttribute::getValue).orElse(null);
                    String artifactId = classpathAttributes.stream().filter(attr ->
                            "gradle.module.name".equals(attr.getName())).findFirst()
                        .map(ClasspathAttribute::getValue).orElse(null);
                    String version = classpathAttributes.stream().filter(attr ->
                            "gradle.module.version".equals(attr.getName())).findFirst()
                        .map(ClasspathAttribute::getValue).orElse(null);
                    // TODO: 依赖模块名
                    dependencyList.add(new Dependency(groupId, artifactId, version,
                        groupId, externalDependency.getFile(),
                        externalDependency.getSource()));
                }
            });

            moduleList.addAll(getJdkModuleList(editor));
            ideaProject.getModules().forEach(module -> module.getContentRoots().forEach(root -> moduleList.add(
                new ProjectModule(module.getName(), ProjectModule.Location.PROJECT, List.of(),
                    root.getSourceDirectories().stream().map(SourceDirectory::getDirectory).toList(),
                    root.getResourceDirectories().stream().map(SourceDirectory::getDirectory).toList(),
                    root.getTestDirectories().stream().map(SourceDirectory::getDirectory).toList(),
                    root.getTestResourceDirectories().stream().map(SourceDirectory::getDirectory).toList()))));
            dependencyList.forEach(dep -> moduleList.add(new ProjectModule(dep.getModuleName(),
                ProjectModule.Location.DEPENDENCY, List.of(), List.of(), List.of(), List.of(), List.of())));

            // TODO: 获取项目GAV
            return new ProjectModel("g", ideaProject.getName(), "v", dependencyList, moduleList);
        }
    }

    @Override
    public Future<Integer> compile(Consumer<String> onOutput) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        // TODO: 编译
        return future;
    }

    public static class GradleModel extends ProjectModel {
        public GradleModel(String groupId, String artifactId, String version,
                           List<Dependency> dependencyList, List<ProjectModule> moduleList) {
            super(groupId, artifactId, version, dependencyList, moduleList);
        }
    }

    public static class GradleType extends BuildToolType {
        private static final String SETTINGS = ResourceUtil.readUtf8Str("fileTemplates/gradleSettings.txt");
        private static final String BUILD = ResourceUtil.readUtf8Str("fileTemplates/gradleBuild.txt");

        @Override
        protected String name() {
            return "GRADLE";
        }

        @Override
        public Class<GradleModel> getModelType() {
            return GradleModel.class;
        }

        @Override
        public void generateBuildScript(Project project, Jdk jdk, String groupId, String artifactId) {
            FileUtil.writeUtf8String(SETTINGS.replace("{artifactId}", artifactId),
                new File(project.getProjectDir(), "settings.gradle.kts"));
            FileUtil.writeUtf8String(BUILD.replace("{groupId}", groupId),
                new File(project.getProjectDir(), "build.gradle.kts"));
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
