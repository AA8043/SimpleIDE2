package org.a8043.simpleIDE.project.runnables;

import cn.hutool.json.JSONObject;
import javafx.scene.control.Tab;
import lombok.AccessLevel;
import lombok.Getter;
import org.a8043.simpleIDE.project.ProjectEditor;

public abstract class RunnableTask {
    @Getter(AccessLevel.PROTECTED)
    private final ProjectEditor editor;
    @Getter
    private final String name;

    public RunnableTask(ProjectEditor editor, JSONObject json) {
        this.editor = editor;
        name = json.getStr("name");
    }

    public abstract Runner createRunner(Tab tab);
}
