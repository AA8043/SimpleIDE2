package org.a8043.simpleIDE.project;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.io.File;
import java.util.List;
import java.util.stream.Stream;

@AllArgsConstructor
@Getter
@Setter
public class ProjectModule {
    private String name;
    private ProjectModule.Location location;
    private List<ProjectModule> childList;
    private List<File> srcDir;
    private List<File> resourcesDir;
    private List<File> testSrcDir;
    private List<File> testResourcesDir;

    public List<File> getSrcDirList() {
        return Stream.of(srcDir, testSrcDir).flatMap(List::stream).toList();
    }

    public enum Location {
        PROJECT, JDK, DEPENDENCY
    }
}
