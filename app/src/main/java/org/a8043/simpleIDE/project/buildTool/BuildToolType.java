package org.a8043.simpleIDE.project.buildTool;

import cn.hutool.core.convert.AbstractConverter;
import cn.hutool.core.convert.ConverterRegistry;
import lombok.AllArgsConstructor;
import org.a8043.simpleIDE.project.Jdk;
import org.a8043.simpleIDE.project.Project;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.project.ProjectModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@AllArgsConstructor
public abstract class BuildToolType {
    public static final Maven.MavenType MAVEN = new Maven.MavenType();
    public static final Gradle.GradleType GRADLE = new Gradle.GradleType();
    public static final UnknownType UNKNOWN = new UnknownType();
    public static final List<BuildToolType> TYPE_LIST = new ArrayList<>();

    static {
        register(MAVEN);
        register(GRADLE);
        register(UNKNOWN);

        ConverterRegistry.getInstance().putCustom(BuildToolType.class, new AbstractConverter<BuildToolType>() {
            @Override
            protected BuildToolType convertInternal(Object value) {
                if (value instanceof String str) {
                    return getByName(str);
                }
                return null;
            }
        });
    }

    public static void register(BuildToolType type) {
        TYPE_LIST.add(type);
    }

    public static BuildToolType getByName(String name) {
        return TYPE_LIST.stream().filter(type -> Objects.equals(type.name(), name)).findFirst().orElse(null);
    }

    public abstract String name();

    public abstract Class<? extends ProjectModel> getModelType();

    public abstract void generateBuildScript(Project project, Jdk jdk, String groupId, String artifactId);

    public abstract BuildTool newBuildTool(ProjectEditor editor);

    public abstract boolean isUseThis(Project project);

    public static BuildToolType recognition(Project project) {
        return TYPE_LIST.stream().filter(type -> type.isUseThis(project)).findFirst().orElse(UNKNOWN);
    }

    public static class UnknownType extends BuildToolType {
        @Override
        public String name() {
            return "UNKNOWN";
        }

        @Override
        public Class<ProjectModel> getModelType() {
            return ProjectModel.class;
        }

        @Override
        public void generateBuildScript(Project project, Jdk jdk, String groupId, String artifactId) {
        }

        @Override
        public BuildTool newBuildTool(ProjectEditor editor) {
            return null;
        }

        @Override
        public boolean isUseThis(Project project) {
            return false;
        }
    }
}
