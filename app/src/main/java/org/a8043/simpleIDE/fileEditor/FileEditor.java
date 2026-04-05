package org.a8043.simpleIDE.fileEditor;

import lombok.Getter;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.List;

@Getter
public abstract class FileEditor {
    private final ControllableFile file;
    private final ProjectEditor editor;

    public FileEditor(ControllableFile file, ProjectEditor editor) {
        this.file = file;
        this.editor = editor;
    }

    public final String read() {
        return file.read();
    }

    public final void setContent(String content) {
        file.setContent(content);
        onContentChanged();
    }

    protected abstract void onContentChanged();

    public final String getContent() {
        return file.getContent();
    }

    public abstract String getHighlightingStyle();

    public abstract List<CompleteItem> computeCompletion(int caretPosition);

    public final StyleSpans<Collection<String>> computeHighlighting() {
        StyleSpans<Collection<String>> originalSpans = doComputeHighlighting();
        return fillUnstyledParts(originalSpans, file.getContent(), List.of("default"));
    }

    protected abstract StyleSpans<Collection<String>> doComputeHighlighting();

    private static StyleSpans<Collection<String>> fillUnstyledParts(StyleSpans<Collection<String>> originalSpans,
                                                                    String text, Collection<String> defaultStyle) {
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        int lastPosition = 0;
        for (StyleSpan<Collection<String>> span : originalSpans) {
            int spanLength = span.getLength();
            if (span.getStyle().isEmpty()) {
                spansBuilder.add(defaultStyle, spanLength);
            } else {
                spansBuilder.add(span.getStyle(), spanLength);
            }
            lastPosition += spanLength;
        }
        if (lastPosition < text.length()) {
            spansBuilder.add(defaultStyle, text.length() - lastPosition);
        }
        return spansBuilder.create();
    }

    public abstract String computeHoverTip(int position);

    public abstract List<CodeError> getProblemList();
}
