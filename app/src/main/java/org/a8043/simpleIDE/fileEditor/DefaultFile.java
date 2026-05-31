package org.a8043.simpleIDE.fileEditor;

import org.a8043.simpleIDE.project.ProjectEditor;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.List;

public class DefaultFile extends FileEditor {
    public DefaultFile(ControllableFile file, ProjectEditor editor) {
        super(file, editor);
    }

    @Override
    public String getHighlightingStyle() {
        return ".white { -fx-fill: white; }";
    }

    @Override
    public StyleSpans<Collection<String>> doComputeHighlighting() {
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        spansBuilder.add(List.of("white"), getContent().length());
        return spansBuilder.create();
    }

    @Override
    public String computeHoverTip(int position) {
        return "";
    }

    @Override
    public JumpTarget jump(int position) {
        return null;
    }

    @Override
    public List<CodeError> getProblemList() {
        return List.of();
    }

    @Override
    public List<CompleteItem> computeCompletion(int caretPosition) {
        return List.of();
    }

    @Override
    protected void onContentChanged() {
    }
}
