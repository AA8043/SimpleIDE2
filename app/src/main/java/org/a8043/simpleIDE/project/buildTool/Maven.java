package org.a8043.simpleIDE.project.buildTool;

import cn.hutool.core.annotation.PropIgnore;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.a8043.simpleIDE.project.*;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Resource;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.shared.invoker.*;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Consumer;

@Slf4j
@Getter
public class Maven extends BuildTool {
    private final File pomFile;

    public Maven(ProjectEditor editor) {
        super(editor);
        pomFile = new File(editor.getProject().getProjectDir(), "pom.xml");
    }

    @Override
    public ProjectModel sync(ProjectEditor editor) {
        Model model = getModel(pomFile);
//        List<ProjectModule> moduleList = new ArrayList<>();
//
//        return new MavenModel(model.getGroupId(), model.getArtifactId(), model.getVersion(),
//            dependencyList, moduleList, model);
        AnalysisResult result = new AnalysisResult();
        result.moduleList.addAll(getJdkModuleList(editor));
        List<Dependency> dependencyList = model.getDependencies().stream().map(Dependency::fromMaven).toList();
        result.moduleList.addAll(dependencyList.stream().map(dependency -> new ProjectModule(dependency.getModuleName(),
            ProjectModule.Location.DEPENDENCY, List.of(), List.of(), List.of(), List.of(), List.of())).toList());
        parseModuleRecursively(model, editor.getProject().getProjectDir(),
            ProjectModule.Location.PROJECT, result, new HashMap<>());
        return new MavenModel(model.getGroupId(), model.getArtifactId(), model.getVersion(),
            result.dependencieList, result.moduleList, model);
    }

    @SneakyThrows
    private static void parseModuleRecursively(Model model, File baseDir, ProjectModule.Location location,
                                               AnalysisResult result, Map<String, Model> parsedModels) {
        String moduleKey = model.getGroupId() + ":" + model.getArtifactId() + ":" + model.getVersion();

        if (parsedModels.containsKey(moduleKey)) {
            return;
        }
        parsedModels.put(moduleKey, model);

        ProjectModule currentModule = extractModuleInfo(model, baseDir, location);
        result.moduleList.add(currentModule);

        if (model.getDependencies() != null) {
            for (org.apache.maven.model.Dependency mvnDep : model.getDependencies()) {
                if ("test".equals(mvnDep.getScope()) || "provided".equals(mvnDep.getScope())) {
                    continue;
                }
                result.dependencieList.add(Dependency.fromMaven(mvnDep));
            }
        }

        if (model.getModules() != null && !model.getModules().isEmpty()) {
            model.getModules().stream().map(modulePath -> new File(baseDir, modulePath + "/pom.xml"))
                .filter(File::exists).forEach(modulePomFile ->
                    parseModuleRecursively(getModel(modulePomFile), modulePomFile.getParentFile(),
                        ProjectModule.Location.PROJECT, result, parsedModels));
        }
    }

    private static Model getModel(File pomFile) {
        Model moduleModel;
        try (InputStream inputStream = FileUtil.getInputStream(pomFile)) {
            moduleModel = new MavenXpp3Reader().read(inputStream);
        } catch (IOException | XmlPullParserException e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
        return moduleModel;
    }

    private static ProjectModule extractModuleInfo(Model model, File baseDir, ProjectModule.Location location) {
        String moduleName = model.getArtifactId();
        List<ProjectModule> childList = new ArrayList<>();
        List<File> srcDir = new ArrayList<>();
        List<File> resourcesDir = new ArrayList<>();
        List<File> testSrcDir = new ArrayList<>();
        List<File> testResourcesDir = new ArrayList<>();

        Build build = model.getBuild();
        if (build != null) {
            if (build.getSourceDirectory() != null) {
                srcDir.add(new File(baseDir, build.getSourceDirectory()));
            } else {
                srcDir.add(new File(baseDir, "src/main/java"));
            }

            if (build.getResources() != null) {
                for (Resource resource : build.getResources()) {
                    if (resource.getDirectory() != null) {
                        resourcesDir.add(new File(baseDir, resource.getDirectory()));
                    }
                }
            }
            if (resourcesDir.isEmpty()) {
                resourcesDir.add(new File(baseDir, "src/main/resources"));
            }

            if (build.getTestSourceDirectory() != null) {
                testSrcDir.add(new File(baseDir, build.getTestSourceDirectory()));
            } else {
                testSrcDir.add(new File(baseDir, "src/test/java"));
            }

            if (build.getTestResources() != null) {
                for (Resource resource : build.getTestResources()) {
                    if (resource.getDirectory() != null) {
                        testResourcesDir.add(new File(baseDir, resource.getDirectory()));
                    }
                }
            }
            if (testResourcesDir.isEmpty()) {
                testResourcesDir.add(new File(baseDir, "src/test/resources"));
            }
        } else {
            srcDir.add(new File(baseDir, "src/main/java"));
            resourcesDir.add(new File(baseDir, "src/main/resources"));
            testSrcDir.add(new File(baseDir, "src/test/java"));
            testResourcesDir.add(new File(baseDir, "src/test/resources"));
        }

        return new ProjectModule(moduleName, location, childList,
            srcDir, resourcesDir, testSrcDir, testResourcesDir);
    }

    public static class AnalysisResult {
        private final List<ProjectModule> moduleList = new ArrayList<>();
        private final List<Dependency> dependencieList = new ArrayList<>();
    }

    @Override
    public Future<Integer> compile(Consumer<String> onOutput) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        run(List.of("compile"), onOutput, future::complete);
        return future;
    }

    private void run(List<String> goalList, Consumer<String> onOutput, Consumer<Integer> onFinish) {
        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(pomFile);
        request.addArgs(goalList);
        request.setBaseDirectory(getEditor().getProject().getProjectDir());
        request.setJavaHome(getEditor().getJdk().getPath());
        request.setOutputHandler(onOutput::accept);
        request.setErrorHandler(onOutput::accept);

        Invoker invoker = new DefaultInvoker();
        File mavenDir = getEditor().getConfig().getBuildToolPath();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            invoker.setMavenHome(new File(mavenDir, "mvn.cmd"));
        } else {
            invoker.setMavenHome(new File(mavenDir, "mvn"));
        }

        new Thread(() -> {
            InvocationResult execute;
            try {
                execute = invoker.execute(request);
            } catch (MavenInvocationException e) {
                throw new BuildToolException(e);
            }
            onFinish.accept(execute.getExitCode());
        }).start();
    }

    @Override
    public List<ModuleRecord> getModuleList() {
        return ((MavenModel) editor.getProjectModel()).getModel().getModules().stream().map(moduleName -> {
            String[] split = moduleName.split("[/\\\\]");
            String name = moduleName.split("[/\\\\]")[split.length - 1];
            File dir = new File(getEditor().getProject().getProjectDir(), moduleName);

            Model model = getModel(new File(dir, "pom.xml"));

            return new ModuleRecord(name, dir, model);
        }).toList();
    }

    public static class MavenModel extends ProjectModel {
        @Getter
        @PropIgnore
        private final Model model;

        public MavenModel(String groupId, String artifactId, String version,
                          List<Dependency> dependencyList, List<ProjectModule> moduleList, Model model) {
            super(groupId, artifactId, version, dependencyList, moduleList);
            this.model = model;
        }
    }

    public static class MavenType extends BuildToolType {
        @Override
        public String name() {
            return "MAVEN";
        }

        @Override
        public void generateBuildScript(Project project, Jdk jdk, String groupId, String artifactId) {
            String content = ResourceUtil.readUtf8Str("fileTemplates/mavenPom.xml")
                .replace("{groupId}", groupId)
                .replace("{artifactId}", artifactId)
                .replace("{javaVersion}", jdk.getVersion().split("\\.")[0]);
            FileUtil.writeUtf8String(content, new File(project.getProjectDir(), "pom.xml"));
        }

        @Override
        public BuildTool newBuildTool(ProjectEditor editor) {
            return new Maven(editor);
        }

        @Override
        public boolean isUseThis(Project project) {
            return new File(project.getProjectDir(), "pom.xml").exists();
        }
    }
}
