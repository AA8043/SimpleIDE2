package org.a8043.simpleIDE.fileEditor;

import lombok.Value;

@Value
public class CodeError {
    int start;
    int end;
    String message;
    Type type;

    public enum Type {
        SYNTAX_ERROR, SEMANTIC_ERROR, WARNING
    }
}
