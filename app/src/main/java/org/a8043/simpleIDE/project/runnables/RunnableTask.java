package org.a8043.simpleIDE.project.runnables;

import cn.hutool.json.JSONObject;
import javafx.scene.Node;
import lombok.AccessLevel;
import lombok.Getter;
import org.a8043.simpleIDE.project.ProjectEditor;

public abstract class RunnableTask {
    @Getter(AccessLevel.PROTECTED)
    private final ProjectEditor editor;
    @Getter
    private final JSONObject json;

    protected RunnableTask(ProjectEditor editor, JSONObject json) {
        this.editor = editor;
        this.json = json;
    }

    public final String getName() {
        return json != null ? json.getStr("name") : null;
    }

    public abstract Runner createRunner();

    public abstract Node createManager();

    private Node manager;

    public Node getManager() {
        return manager == null ? (manager = createManager()) : manager;
    }

    public abstract Node createListItem();
}
