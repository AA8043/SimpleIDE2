package org.a8043.simpleIDE.project.buildTool;

import cn.hutool.core.convert.AbstractConverter;
import cn.hutool.core.convert.ConverterRegistry;
import cn.hutool.json.JSONObject;
import org.a8043.simpleIDE.project.Jdk;
import org.a8043.simpleIDE.project.Project;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.project.ProjectModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 构建工具类型
 */
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
                if (value instanceof JSONObject json) {
                    return getByName(json.getStr("name"));
                }
                return null;
            }
        });
    }

    /**
     * 注册构建工具类型
     * @param type 构建工具类型
     */
    public static void register(BuildToolType type) {
        TYPE_LIST.add(type);
    }

    public static BuildToolType getByName(String name) {
        return TYPE_LIST.stream().filter(type -> Objects.equals(type.getName(), name)).findFirst().orElse(null);
    }

    private String name;

    public final String getName() {
        return name != null ? name : (name = name());
    }

    /**
     * 获取构建工具名称
     * @return 构建工具名称
     */
    protected abstract String name();

    /**
     * 获取项目模型类型
     * @return 项目模型类型
     */
    public abstract Class<? extends ProjectModel> getModelType();

    /**
     * 生成构建脚本
     * @param project 项目
     * @param jdk JDK
     * @param groupId GroupId
     * @param artifactId ArtifactId
     */
    public abstract void generateBuildScript(Project project, Jdk jdk, String groupId, String artifactId);

    /**
     * 创建构建工具
     * @param editor 项目编辑器
     * @return 构建工具
     */
    public abstract BuildTool newBuildTool(ProjectEditor editor);

    /**
     * 判断项目是否使用此构建工具
     * @param project 项目
     * @return 是否使用此构建工具
     */
    public abstract boolean isUseThis(Project project);

    /**
     * 识别构建工具
     * @param project 项目
     * @return 构建工具
     */
    public static BuildToolType recognition(Project project) {
        return TYPE_LIST.stream().filter(type -> type.isUseThis(project)).findFirst().orElse(UNKNOWN);
    }

    public static class UnknownType extends BuildToolType {
        @Override
        protected String name() {
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
