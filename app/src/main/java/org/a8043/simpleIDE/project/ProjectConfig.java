package org.a8043.simpleIDE.project;

import cn.hutool.core.annotation.Alias;
import cn.hutool.json.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.project.buildTool.BuildToolType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class ProjectConfig {
    private File jdkPath;
    private BuildToolType buildToolType;
    private File buildToolPath;
    private List<JSONObject> runnableJsonList;
    @Alias("index.onlyIndexStartsWith")
    private String onlyIndexStartsWith;

    public static ProjectConfig fromMaven(ProjectEditor editor) {
        return new ProjectConfig(new File(editor.getProject().getProjectDir(), "jdk"),
            BuildToolType.MAVEN, Main.instance.getSettings().getDefaultMavenPath(),
            new ArrayList<>(), "");
    }

    public static ProjectConfig fromGradle(ProjectEditor editor) {
        return new ProjectConfig(new File(editor.getProject().getProjectDir(), "jdk"),
            BuildToolType.GRADLE, Main.instance.getSettings().getDefaultGradlePath(),
            new ArrayList<>(), "");
    }

    public static ProjectConfig fromDefault(ProjectEditor editor) {
        return new ProjectConfig(new File(editor.getProject().getProjectDir(), "jdk"),
            BuildToolType.UNKNOWN, null, new ArrayList<>(), "");
    }
}
