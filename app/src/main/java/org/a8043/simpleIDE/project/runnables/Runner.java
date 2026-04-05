package org.a8043.simpleIDE.project.runnables;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

public abstract class Runner {
    @Getter
    private final Map<String, Boolean> optionMap = new HashMap<>();

    public abstract void run();

    public abstract void close();
}
