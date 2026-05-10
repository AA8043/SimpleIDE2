package org.a8043.simpleIDE.project.types;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONObject;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import org.a8043.simpleIDE.project.Jdk;
import org.a8043.simpleIDE.project.Project;
import org.a8043.simpleIDE.project.ProjectConfig;
import org.a8043.simpleIDE.project.buildTool.BuildToolType;
import org.a8043.simpleIDE.resource.ResourceManager;
import org.a8043.simpleIDE.views.NewProjectModal;

import java.io.File;
import java.util.ArrayList;

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
        FileUtil.writeUtf8String(new JSONObject(new ProjectConfig(jdk.getPath(),
                buildToolType, null, new ArrayList<>(), "")).toString(),
            new File(configDir, "editor.json"));
        buildToolType.generateBuildScript(project, jdk, groupId, artifactId);
    }

    @Override
    public Node createConfigurationPane() {
        return new Pane();
    }
}
