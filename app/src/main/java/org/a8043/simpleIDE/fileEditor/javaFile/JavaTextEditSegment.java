package org.a8043.simpleIDE.fileEditor.javaFile;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class JavaTextEditSegment {
    private final int oldStart;
    private final int oldEnd;
    private final int newStart;
    private final int newEnd;
}
