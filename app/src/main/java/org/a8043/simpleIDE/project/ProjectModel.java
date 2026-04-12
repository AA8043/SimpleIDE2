package org.a8043.simpleIDE.project;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.a8043.simpleIDE.project.buildTool.Dependency;

import java.io.File;
import java.util.List;

@AllArgsConstructor
@Getter
@Setter
public class ProjectModel {
    private String groupId;
    private String artifactId;
    private String version;
    private List<Dependency> dependencyList;
    private List<ProjectModule> moduleList;

    public List<File> getSrcDirList() {
        return moduleList.stream().flatMap(module -> module.getSrcDir().stream()).toList();
    }
}
