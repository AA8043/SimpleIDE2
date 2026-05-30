package org.a8043.simpleIDE.project.buildTool;

import lombok.AccessLevel;
import lombok.Getter;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.project.ProjectModel;
import org.a8043.simpleIDE.project.ProjectModule;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * 构建工具
 */
public abstract class BuildTool {
    @Getter(AccessLevel.PROTECTED)
    ProjectEditor editor;

    protected BuildTool(ProjectEditor editor) {
        this.editor = editor;
    }

    /**
     * 创建项目模型
     * @param editor 项目编辑器
     * @return 项目模型
     */
    public abstract ProjectModel sync(ProjectEditor editor);

    protected static List<ProjectModule> getJdkModuleList(ProjectEditor editor) {
        List<String> addedList = new ArrayList<>();
        return editor.getIndex().getStandardLibraryZip().stream().map(entry -> {
            String name = entry.getName().split("/")[0];
            if (addedList.contains(name)) {
                return null;
            }
            addedList.add(name);
            return new ProjectModule(name, ProjectModule.Location.JDK, List.of(),
                List.of(), List.of(), List.of(), List.of());
        }).filter(Objects::nonNull).toList();
    }

    /**
     * 编译
     * @param onOutput 输出回调
     * @return 编译结果Future
     */
    public abstract Future<Integer> compile(Consumer<String> onOutput);

    /**
     * 获取运行时类路径列表
     * @return 运行时类路径列表
     */
    public abstract List<File> getRuntimeClasspathList();
}
