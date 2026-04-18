package org.a8043.simpleIDE.project.runnables;

import javafx.scene.Node;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

public abstract class Runner {
    @Getter
    private final Map<String, Boolean> optionMap = new HashMap<>();

    public abstract Node createContent();

    public abstract void run();

    public abstract void close();
}
