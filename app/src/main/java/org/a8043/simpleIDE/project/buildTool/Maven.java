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
    private static final File MAVEN_LOCAL_REPOSITORY = new File(System.getProperty("user.home"), ".m2/repository");
    private final File pomFile;

    public Maven(ProjectEditor editor) {
        super(editor);
        pomFile = new File(editor.getProject().getProjectDir(), "pom.xml");
    }

    @Override
    public MavenModel sync(ProjectEditor editor) {
        Model model = getModel(pomFile);
        List<ProjectModule> moduleList = new ArrayList<>(getJdkModuleList(editor));
        List<Dependency> dependencyList = model.getDependencies().stream().map(Maven::fromMaven).toList();
        moduleList.addAll(dependencyList.stream().map(dependency -> new ProjectModule(dependency.getModuleName(),
            ProjectModule.Location.DEPENDENCY, List.of(), List.of(), List.of(), List.of(), List.of())).toList());
        parseModuleRecursively(model, editor.getProject().getProjectDir(),
            ProjectModule.Location.PROJECT, moduleList, new HashMap<>());
        return new MavenModel(model.getGroupId(), model.getArtifactId(), model.getVersion(),
            dependencyList, moduleList, model);
    }

    @SneakyThrows
    private static void parseModuleRecursively(Model model, File baseDir, ProjectModule.Location location,
                                               List<ProjectModule> moduleList, Map<String, Model> parsedModels) {
        String moduleKey = model.getGroupId() + ":" + model.getArtifactId() + ":" + model.getVersion();

        if (parsedModels.containsKey(moduleKey)) {
            return;
        }
        parsedModels.put(moduleKey, model);

        ProjectModule currentModule = extractModuleInfo(model, baseDir, location);
        moduleList.add(currentModule);

        if (model.getModules() != null && !model.getModules().isEmpty()) {
            model.getModules().stream().map(modulePath -> new File(baseDir, modulePath + "/pom.xml"))
                .filter(File::exists).forEach(modulePomFile ->
                    parseModuleRecursively(getModel(modulePomFile), modulePomFile.getParentFile(),
                        ProjectModule.Location.PROJECT, moduleList, parsedModels));
        }
    }

    public static Dependency fromMaven(org.apache.maven.model.Dependency dependency) {
        String groupId = dependency.getGroupId();
        String artifactId = dependency.getArtifactId();
        String version = dependency.getVersion();

        File dir = new File(MAVEN_LOCAL_REPOSITORY,
            groupId.replace(".", "/") + "/" +
            artifactId + "/" + version);
        String baseFileName = artifactId + "-" + version;
        String classifier = dependency.getClassifier();
        if (classifier != null && !classifier.isEmpty()) {
            baseFileName += "-" + classifier;
        }

        String type = dependency.getType() != null ? dependency.getType() : "jar";
        return new Dependency(groupId, artifactId, version, "",
            new File(dir, baseFileName + "." + type), new File(dir, baseFileName + "-sources.jar"));
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

    @Override
    public Future<Integer> compile(Consumer<String> onOutput) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        run(List.of("compile"), onOutput, future::complete);
        return future;
    }

    @Override
    public List<File> getRuntimeClasspathList() {
        return List.of(new File(getEditor().getProject().getProjectDir(), "target/classes"));
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
        private static final String POM = ResourceUtil.readUtf8Str("fileTemplates/mavenPom.txt");

        @Override
        protected String name() {
            return "MAVEN";
        }

        @Override
        public Class<MavenModel> getModelType() {
            return MavenModel.class;
        }

        @Override
        public void generateBuildScript(Project project, Jdk jdk, String groupId, String artifactId) {
            FileUtil.writeUtf8String(POM.replace("{groupId}", groupId)
                    .replace("{artifactId}", artifactId)
                    .replace("{javaVersion}", jdk.getVersion().split("\\.")[0]),
                new File(project.getProjectDir(), "pom.xml"));
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
