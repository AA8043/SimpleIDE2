package org.a8043.simpleIDE.project;

import lombok.Getter;

import java.io.File;

@Getter
public class Project {
    private final String name;
    private final File projectDir;

    public Project(String name, File projectDir) {
        this.name = name;
        this.projectDir = projectDir;
    }

    public ProjectEditor open() {
        return new ProjectEditor(this);
    }
}
