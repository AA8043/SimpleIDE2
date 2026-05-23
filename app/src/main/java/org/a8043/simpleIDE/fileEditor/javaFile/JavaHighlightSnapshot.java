package org.a8043.simpleIDE.fileEditor.javaFile;

import com.github.javaparser.ast.CompilationUnit;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class JavaHighlightSnapshot {
    private final CompilationUnit compilationUnit;
    private final String content;
}
