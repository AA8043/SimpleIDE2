package org.a8043.simpleIDE.project;

import cn.hutool.json.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.project.buildTool.BuildToolType;
import org.a8043.simpleIDE.project.buildTool.Gradle;
import org.a8043.simpleIDE.project.buildTool.Maven;
import org.a8043.simpleIDE.util.config.ConfigClass;
import org.a8043.simpleIDE.util.config.Item;
import org.apache.maven.model.Build;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ConfigClass
public class ProjectConfig {
    @Item
    private File jdkPath;
    @Item
    private BuildToolType buildToolType;
    @Item
    private File buildToolPath;
    @Item
    private File srcDir;
    @Item
    private File testSrcDir;
    @Item
    private List<File> resourcesDir;
    @Item
    private List<JSONObject> runnableJsonList;
    @Item("index.#")
    private String onlyIndexStartsWith;

    public static ProjectConfig fromMaven(ProjectEditor editor) {
        File projectDir = editor.getProject().getProjectDir();
        Build build = ((Maven) editor.getBuildTool()).getModel().getBuild();
        return new ProjectConfig(new File(editor.getProject().getProjectDir(), "jdk"),
            BuildToolType.MAVEN, Main.instance.getSettings().getDefaultMavenPath(),
            new File(projectDir, build.getSourceDirectory()),
            new File(projectDir, build.getTestSourceDirectory()),
            build.getResources().stream().map(resource -> new File(projectDir, resource.getDirectory())).toList(),
            new ArrayList<>(), "");
    }

    public static ProjectConfig fromGradle(ProjectEditor editor) {
        Gradle gradle = (Gradle) editor.getBuildTool();
        // TODO: 从gradle生成config
        return new ProjectConfig(new File(editor.getProject().getProjectDir(), "jdk"),
            BuildToolType.GRADLE, Main.instance.getSettings().getDefaultGradlePath(),
            null, null, null, new ArrayList<>(), "");
    }

    public static ProjectConfig fromDefault(ProjectEditor editor) {
        Project project = editor.getProject();
        File projectDir = project.getProjectDir();
        return new ProjectConfig(new File(editor.getProject().getProjectDir(), "jdk"),
            BuildToolType.UNKNOWN, null,
            new File(projectDir, "src/main/java"),
            new File(projectDir, "src/test/java"),
            new ArrayList<>(Collections.singleton(new File(projectDir, "src/main/resources"))),
            new ArrayList<>(), "");
    }
}
