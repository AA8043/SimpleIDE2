package org.a8043.simpleIDE.fileEditor.javaFile;

import cn.hutool.core.collection.CollUtil;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import lombok.Getter;
import lombok.Setter;
import org.a8043.simpleIDE.fileEditor.CodeError;
import org.a8043.simpleIDE.project.index.IndexPoint;
import org.a8043.simpleIDE.util.FixedList;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Getter
public class JavaFileState {
    private final List<ParseResult<CompilationUnit>> parseResultHistoryList = new FixedList<>(10);
    private final List<String> contentHistoryList = new FixedList<>(10);
    private final List<CodeError> problemList = new ArrayList<>();
    private final List<JavaSyntaxHighlight> problemHighlightList = new ArrayList<>();
    @Setter
    private List<JavaTextEditSegment> pendingHighlightEdits;
    @Setter
    private String pendingHighlightContent;
    @Setter
    private IndexPoint indexPoint;

    public JavaFileState(IndexPoint indexPoint) {
        this.indexPoint = indexPoint;
    }

    public ParseResult<CompilationUnit> getLatestParseResult() {
        return parseResultHistoryList.getLast();
    }

    public CompilationUnit getLatestCompilationUnit() {
        return getLatestParseResult().getResult().orElse(null);
    }

    public CompilationUnit getLatestSuccessfulCompilationUnit() {
        AtomicReference<CompilationUnit> result = new AtomicReference<>();
        CollUtil.reverseNew(parseResultHistoryList).forEach(parseResult -> {
            if (result.get() == null) {
                parseResult.ifSuccessful(result::set);
            }
        });
        return result.get();
    }

    public JavaHighlightSnapshot getLatestSuccessfulHighlightSnapshot() {
        List<ParseResult<CompilationUnit>> parseHistory = CollUtil.reverseNew(parseResultHistoryList);
        List<String> contentHistory = CollUtil.reverseNew(contentHistoryList);
        for (int i = 0; i < Math.min(parseHistory.size(), contentHistory.size()); i++) {
            ParseResult<CompilationUnit> parseResult = parseHistory.get(i);
            CompilationUnit compilationUnit = parseResult.getResult().orElse(null);
            if (parseResult.isSuccessful() && compilationUnit != null) {
                return new JavaHighlightSnapshot(compilationUnit, contentHistory.get(i));
            }
        }
        return null;
    }
}
