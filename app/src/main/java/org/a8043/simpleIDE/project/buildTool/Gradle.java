package org.a8043.simpleIDE.project.buildTool;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import org.a8043.simpleIDE.project.*;
import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleModuleVersion;
import org.gradle.tooling.model.SourceDirectory;
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.idea.IdeaCompilerOutput;
import org.gradle.tooling.model.idea.IdeaModule;
import org.gradle.tooling.model.idea.IdeaProject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Gradle extends BuildTool {
    private static final Pattern STRING_ASSIGNMENT_PATTERN_TEMPLATE =
        Pattern.compile("(?m)^\\s*%s\\s*=\\s*['\"]([^'\"]+)['\"]\\s*$");

    private File mainOutputDir;
    private File resourcesOutputDir;

    public Gradle(ProjectEditor editor) {
        super(editor);
    }

    @Override
    public ProjectModel sync(ProjectEditor editor) {
        List<ProjectModule> moduleList = new ArrayList<>(getJdkModuleList(editor));
        try (ProjectConnection connection = newConnector().connect()) {
            IdeaProject ideaProject = connection.model(IdeaProject.class).setJavaHome(editor.getJdk().getPath()).get();
            EclipseProject eclipseProject =
                connection.model(EclipseProject.class).setJavaHome(editor.getJdk().getPath()).get();

            List<Dependency> dependencyList = new ArrayList<>(collectDependencies(eclipseProject).values());
            dependencyList.forEach(dep -> moduleList.add(new ProjectModule(dep.getModuleName(),
                ProjectModule.Location.DEPENDENCY, List.of(), List.of(), List.of(), List.of(), List.of())));

            Map<String, ModuleAccumulator> accumulatorMap = new LinkedHashMap<>();
            ideaProject.getModules().forEach(module -> {
                ModuleAccumulator accumulator = accumulatorMap.computeIfAbsent(module.getName(), ignored ->
                    new ModuleAccumulator(module.getName()));
                module.getContentRoots().forEach(root -> {
                    accumulator.srcDir.addAll(root.getSourceDirectories().stream()
                        .map(SourceDirectory::getDirectory).toList());
                    accumulator.resourcesDir.addAll(root.getResourceDirectories().stream()
                        .map(SourceDirectory::getDirectory).toList());
                    accumulator.testSrcDir.addAll(root.getTestDirectories().stream()
                        .map(SourceDirectory::getDirectory).toList());
                    accumulator.testResourcesDir.addAll(root.getTestResourceDirectories().stream()
                        .map(SourceDirectory::getDirectory).toList());
                });
                if (isRootModule(module)) {
                    updateRuntimeOutput(module);
                }
            });
            accumulatorMap.values().forEach(accumulator -> moduleList.add(accumulator.toProjectModule()));

            String artifactId = ideaProject.getName();
            String groupId = resolveProjectProperty(editor.getProject().getProjectDir(), "group", "");
            String version = resolveProjectProperty(editor.getProject().getProjectDir(), "version", "unspecified");
            return new GradleModel(groupId, artifactId, version, dependencyList, moduleList);
        }
    }

    @Override
    public Future<Integer> compile(Consumer<String> onOutput) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        new Thread(() -> {
            try (ProjectConnection connection = newConnector().connect()) {
                BuildLauncher launcher = connection.newBuild()
                    .forTasks("classes")
                    .setJavaHome(getEditor().getJdk().getPath())
                    .addArguments("--console=plain")
                    .setStandardOutput(new LineForwardingOutputStream(onOutput))
                    .setStandardError(new LineForwardingOutputStream(onOutput));
                launcher.run();
                future.complete(0);
            } catch (Exception e) {
                onOutput.accept(e.getMessage() != null ? e.getMessage() : e.toString());
                future.complete(1);
            }
        }, "gradle-compile").start();
        return future;
    }

    @Override
    public List<File> getRuntimeClasspathList() {
        File projectDir = getEditor().getProject().getProjectDir();
        File resolvedMainOutputDir = mainOutputDir != null ? mainOutputDir : new File(projectDir, "build/classes/java/main");
        File resolvedResourcesOutputDir =
            resourcesOutputDir != null ? resourcesOutputDir : new File(projectDir, "build/resources/main");
        return List.of(resolvedMainOutputDir, resolvedResourcesOutputDir);
    }

    private GradleConnector newConnector() {
        GradleConnector connector = GradleConnector.newConnector()
            .forProjectDirectory(getEditor().getProject().getProjectDir())
            .useBuildDistribution();
        File buildToolPath = getEditor().getConfig() != null ? getEditor().getConfig().getBuildToolPath() : null;
        if (isGradleInstallation(buildToolPath)) {
            connector.useInstallation(buildToolPath);
        }
        return connector;
    }

    private Map<String, Dependency> collectDependencies(EclipseProject project) {
        Map<String, Dependency> dependencyMap = new LinkedHashMap<>();
        collectDependencies(project, dependencyMap);
        return dependencyMap;
    }

    private void collectDependencies(EclipseProject project, Map<String, Dependency> dependencyMap) {
        project.getClasspath().forEach(entry -> {
            if (!(entry instanceof EclipseExternalDependency externalDependency)) {
                return;
            }
            File jarFile = externalDependency.getFile();
            if (jarFile == null) {
                return;
            }

            GradleModuleVersion moduleVersion = externalDependency.getGradleModuleVersion();
            String groupId = moduleVersion != null ? moduleVersion.getGroup() : null;
            String artifactId = moduleVersion != null ? moduleVersion.getName() : FileUtil.mainName(jarFile);
            String version = moduleVersion != null ? moduleVersion.getVersion() : null;
            File sourceJarFile = externalDependency.getSource() != null ? externalDependency.getSource() :
                guessSourceJar(jarFile);
            Dependency dependency = new Dependency(groupId, artifactId, version,
                artifactId != null ? artifactId : FileUtil.mainName(jarFile), jarFile, sourceJarFile);
            dependencyMap.putIfAbsent(buildDependencyKey(dependency), dependency);
        });
        project.getChildren().forEach(child -> collectDependencies(child, dependencyMap));
    }

    private String buildDependencyKey(Dependency dependency) {
        return String.join(":",
            dependency.getGroupId() != null ? dependency.getGroupId() : "",
            dependency.getArtifactId() != null ? dependency.getArtifactId() : "",
            dependency.getVersion() != null ? dependency.getVersion() : "",
            dependency.getJarFile() != null ? dependency.getJarFile().getAbsolutePath() : "");
    }

    private File guessSourceJar(File jarFile) {
        return new File(jarFile.getParentFile(),
            FileUtil.mainName(jarFile) + "-sources." + FileUtil.extName(jarFile));
    }

    private boolean isRootModule(IdeaModule module) {
        return module.getGradleProject() != null && ":".equals(module.getGradleProject().getPath());
    }

    private void updateRuntimeOutput(IdeaModule module) {
        IdeaCompilerOutput compilerOutput = module.getCompilerOutput();
        if (compilerOutput != null && compilerOutput.getOutputDir() != null) {
            mainOutputDir = compilerOutput.getOutputDir();
        }
        File buildDirectory = module.getGradleProject() != null ? module.getGradleProject().getBuildDirectory() : null;
        if (buildDirectory != null) {
            resourcesOutputDir = new File(buildDirectory, "resources/main");
        }
    }

    private boolean isGradleInstallation(File buildToolPath) {
        if (buildToolPath == null || !buildToolPath.isDirectory()) {
            return false;
        }
        return new File(buildToolPath, "bin/gradle").exists() ||
               new File(buildToolPath, "bin/gradle.bat").exists();
    }

    private String resolveProjectProperty(File projectDir, String key, String defaultValue) {
        String fromProperties = readGradleProperties(projectDir).getProperty(key);
        if (fromProperties != null && !fromProperties.isBlank()) {
            return fromProperties;
        }

        for (String buildScriptName : List.of("build.gradle.kts", "build.gradle")) {
            File buildScript = new File(projectDir, buildScriptName);
            if (!buildScript.exists()) {
                continue;
            }
            Matcher matcher = Pattern.compile(
                STRING_ASSIGNMENT_PATTERN_TEMPLATE.pattern().formatted(Pattern.quote(key))).matcher(
                FileUtil.readUtf8String(buildScript));
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return defaultValue;
    }

    private Properties readGradleProperties(File projectDir) {
        Properties properties = new Properties();
        File propertiesFile = new File(projectDir, "gradle.properties");
        if (!propertiesFile.exists()) {
            return properties;
        }
        try (var inputStream = FileUtil.getInputStream(propertiesFile)) {
            properties.load(inputStream);
        } catch (IOException ignored) {
        }
        return properties;
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

    private static class ModuleAccumulator {
        private final String name;
        private final List<File> srcDir = new ArrayList<>();
        private final List<File> resourcesDir = new ArrayList<>();
        private final List<File> testSrcDir = new ArrayList<>();
        private final List<File> testResourcesDir = new ArrayList<>();

        private ModuleAccumulator(String name) {
            this.name = name;
        }

        private ProjectModule toProjectModule() {
            return new ProjectModule(name, ProjectModule.Location.PROJECT, List.of(),
                srcDir, resourcesDir, testSrcDir, testResourcesDir);
        }
    }

    private static class LineForwardingOutputStream extends OutputStream {
        private final Consumer<String> onOutput;
        private final StringBuilder builder = new StringBuilder();

        private LineForwardingOutputStream(Consumer<String> onOutput) {
            this.onOutput = onOutput;
        }

        @Override
        public void write(int b) {
            if (b == '\r') {
                return;
            }
            if (b == '\n') {
                flushBuffer();
                return;
            }
            builder.append((char) b);
        }

        @Override
        public void flush() {
            flushBuffer();
        }

        @Override
        public void close() {
            flushBuffer();
        }

        private void flushBuffer() {
            if (builder.isEmpty()) {
                return;
            }
            onOutput.accept(builder.toString());
            builder.setLength(0);
        }
    }
}
