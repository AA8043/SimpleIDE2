package org.a8043.simpleIDE.project.types;

import cn.hutool.core.io.FileUtil;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import org.a8043.simpleIDE.project.Jdk;
import org.a8043.simpleIDE.project.Project;
import org.a8043.simpleIDE.project.ProjectConfig;
import org.a8043.simpleIDE.project.buildTool.BuildToolType;
import org.a8043.simpleIDE.resource.ResourceManager;
import org.a8043.simpleIDE.util.config.ConfigUtil;
import org.a8043.simpleIDE.views.NewProjectModal;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;

public class JavaProject implements NewProjectModal.ProjectType {
    @Override
    public String name() {
        return ResourceManager.getText("welcome.newProject.javaProject");
    }

    @Override
    public String description() {
        return ResourceManager.getText("welcome.newProject.javaProject.description");
    }

    @Override
    public void create(Project project, BuildToolType buildToolType, Jdk jdk, String groupId, String artifactId) {
        File projectDir = project.getProjectDir();
        if (!new File(projectDir, "src/main/java").mkdirs() ||
            !new File(projectDir, "src/test/java").mkdirs() ||
            !new File(projectDir, "src/main/resources").mkdirs()) {
            throw new RuntimeException();
        }
        File configDir = new File(projectDir, ".simpleIDE");
        FileUtil.writeUtf8String(ConfigUtil.toJson(new ProjectConfig(jdk.getPath(),
            buildToolType, null,
            new File(projectDir, "src/main/java"),
            new File(projectDir, "src/test/java"),
            new ArrayList<>(Collections.singleton(new File(projectDir, "src/main/resources"))),
            new ArrayList<>(), "")).toString(), new File(configDir, "editor.json"));
        buildToolType.generateBuildScript(project, jdk, groupId, artifactId);
    }

    @Override
    public Node createConfigurationPane() {
        return new Pane();
    }
}
