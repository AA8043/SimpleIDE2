package org.a8043.simpleIDE.fileEditor.javaFile;

import com.github.javaparser.Range;

public class JavaSyntaxHighlight {
    final Range range;
    final String styleClass;
    final boolean fromOldText;

    public JavaSyntaxHighlight(Range range, String styleClass) {
        this(range, styleClass, true);
    }

    public JavaSyntaxHighlight(Range range, String styleClass, boolean fromOldText) {
        this.range = range;
        this.styleClass = styleClass;
        this.fromOldText = fromOldText;
    }
}
