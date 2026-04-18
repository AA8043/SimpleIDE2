package org.a8043.simpleIDE.project.runnables;

import cn.hutool.json.JSONObject;
import org.a8043.simpleIDE.project.ProjectEditor;

import java.util.ArrayList;
import java.util.List;

public abstract class RunnableType {
    public static final List<RunnableType> TYPE_LIST = new ArrayList<>();

    static {
        register(new JavaRunnable.Type());
    }

    public static void register(RunnableType type) {
        TYPE_LIST.add(type);
    }

    public abstract String getName();

    public abstract String getDisplayName();

    public abstract RunnableTask createTask(ProjectEditor editor, JSONObject json);
}
