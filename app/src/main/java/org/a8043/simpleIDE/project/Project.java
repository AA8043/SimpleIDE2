package org.a8043.simpleIDE.project;

import lombok.Getter;

import java.io.File;

/**
 * 项目
 */
@Getter
public class Project {
    /**
     * 项目名称
     */
    private final String name;
    /**
     * 项目目录
     */
    private final File projectDir;

    public Project(String name, File projectDir) {
        this.name = name;
        this.projectDir = projectDir;
    }

    /**
     * 打开项目
     * @return 项目编辑器
     */
    public ProjectEditor open() {
        return new ProjectEditor(this);
    }
}
