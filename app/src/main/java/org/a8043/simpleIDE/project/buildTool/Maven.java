package org.a8043.simpleIDE.project.buildTool;

import cn.hutool.core.io.FileUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.shared.invoker.*;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Consumer;

@Slf4j
@Getter
public class Maven extends BuildTool {
    private final File pomFile;
    private final Model model;

    public Maven(ProjectEditor editor) {
        super(editor);
        pomFile = new File(editor.getProject().getProjectDir(), "pom.xml");

        try (InputStream inputStream = FileUtil.getInputStream(pomFile)) {
            model = new MavenXpp3Reader().read(inputStream);
        } catch (IOException | XmlPullParserException e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<Dependency> doGetDependencyList() {
        return model.getDependencies().stream().map(Dependency::fromMaven).toList();
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
    public List<ModuleRecord> doGetModuleList() {
        return model.getModules().stream().map(moduleName -> {
            String[] split = moduleName.split("[/\\\\]");
            String name = moduleName.split("[/\\\\]")[split.length - 1];
            File dir = new File(getEditor().getProject().getProjectDir(), moduleName);

            Model model;
            try (InputStream inputStream = FileUtil.getInputStream(new File(dir, "pom.xml"))) {
                model = new MavenXpp3Reader().read(inputStream);
            } catch (IOException | XmlPullParserException e) {
                log.error(e.getMessage(), e);
                throw new RuntimeException(e);
            }

            return new ModuleRecord(name, dir, model);
        }).toList();
    }
}
