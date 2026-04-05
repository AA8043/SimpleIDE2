package org.a8043.simpleIDE.project;

import lombok.Getter;

import java.io.File;

@Getter
public class Project {
    private final String name;
    private final File projectDir;
    private final File jdkPath;

    public Project(String name, File projectDir, File jdkPath) {
        this.name = name;
        this.projectDir = projectDir;
        this.jdkPath = jdkPath;
    }

    public ProjectEditor open() {
        return new ProjectEditor(this);
    }
}
