package org.a8043.simpleIDE.project.buildTool;

import org.a8043.simpleIDE.project.Project;
import org.a8043.simpleIDE.project.ProjectEditor;

import java.io.File;

public enum BuildToolType {
    MAVEN, GRADLE, UNKNOWN;

    public BuildTool newBuildTool(ProjectEditor editor) {
        return switch (this) {
            case MAVEN -> new Maven(editor);
            case GRADLE -> new Gradle(editor);
            case UNKNOWN -> null;
        };
    }

    public static BuildToolType recognition(Project project) {
        File projectDir = project.getProjectDir();
        if (new File(projectDir, "pom.xml").exists()) {
            return MAVEN;
        } else if (new File(projectDir, "build.gradle").exists()) {
            return GRADLE;
        } else {
            return UNKNOWN;
        }
    }
}
