package org.a8043.simpleIDE.fileEditor.javaFile;

import com.github.javaparser.JavaToken;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.a8043.simpleIDE.util.JavaUtil;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.*;

public class JavaHighlightService {
    private final JavaFileState state;

    public JavaHighlightService(JavaFileState state) {
        this.state = state;
    }

    public StyleSpans<Collection<String>> computeHighlighting(String content) {
        ParseResult<CompilationUnit> parseResult = state.getLatestParseResult();
        if (parseResult.isSuccessful()) {
            CompilationUnit compilationUnit = parseResult.getResult().orElse(null);
            if (compilationUnit != null) {
                return computeSuccess(compilationUnit, content);
            }
        }

        JavaHighlightSnapshot snapshot = state.getLatestSuccessfulHighlightSnapshot();
        if (snapshot != null) {
            List<JavaSyntaxHighlight> highlightList = new ArrayList<>(collectSyntaxHighlights(snapshot.getCompilationUnit()));
            highlightList.addAll(state.getProblemHighlightList());
            highlightList.addAll(collectSyntaxProblemHighlights(parseResult.getProblems()));
            if (state.getPendingHighlightEdits() != null && Objects.equals(state.getPendingHighlightContent(), content)) {
                return createStyleSpans(content, highlightList, state.getPendingHighlightEdits(), snapshot.getContent());
            }
            return createStyleSpans(content, highlightList,
                List.of(createTextEditSegment(createEditRange(snapshot.getContent(), content))), snapshot.getContent());
        }
        return computeFail(content, parseResult.getProblems());
    }

    private StyleSpans<Collection<String>> computeSuccess(CompilationUnit cu, String text) {
        List<JavaSyntaxHighlight> highlightList = new ArrayList<>(collectSyntaxHighlights(cu));
        highlightList.addAll(state.getProblemHighlightList());
        return createStyleSpans(text, highlightList);
    }

    private static List<JavaSyntaxHighlight> collectSyntaxHighlights(CompilationUnit cu) {
        List<JavaSyntaxHighlight> highlightList = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                n.getName().getRange().ifPresent(range ->
                    highlightList.add(new JavaSyntaxHighlight(range, "class-name")));
                super.visit(n, arg);
            }

            @Override
            public void visit(MethodDeclaration n, Void arg) {
                n.getName().getRange().ifPresent(range ->
                    highlightList.add(new JavaSyntaxHighlight(range, "method-name")));
                super.visit(n, arg);
            }

            @Override
            public void visit(StringLiteralExpr n, Void arg) {
                highlightList.add(new JavaSyntaxHighlight(n.getRange().orElse(null), "string-literal"));
                super.visit(n, arg);
            }

            @Override
            public void visit(LineComment n, Void arg) {
                highlightList.add(new JavaSyntaxHighlight(n.getRange().orElse(null), "comment"));
                super.visit(n, arg);
            }

            @Override
            public void visit(BlockComment n, Void arg) {
                highlightList.add(new JavaSyntaxHighlight(n.getRange().orElse(null), "comment"));
                super.visit(n, arg);
            }

            @Override
            public void visit(ClassOrInterfaceType n, Void arg) {
                highlightList.add(new JavaSyntaxHighlight(n.getRange().orElse(null), "var-type"));
                super.visit(n, arg);
            }

            @Override
            public void visit(Modifier n, Void arg) {
                highlightList.add(new JavaSyntaxHighlight(n.getRange().orElse(null), "keyword"));
                super.visit(n, arg);
            }

            @Override
            public void visit(ImportDeclaration n, Void arg) {
                n.getTokenRange().ifPresent(tokenRange -> {
                    JavaToken begin = tokenRange.getBegin();
                    highlightList.add(new JavaSyntaxHighlight(begin.getRange().orElse(null), "keyword"));
                    if (n.isStatic()) {
                        begin.getNextToken().ifPresent(nextToken ->
                            highlightList.add(new JavaSyntaxHighlight(nextToken.getRange().orElse(null), "keyword")));
                    }
                });
                super.visit(n, arg);
            }
        }, null);
        highlightList.sort(Comparator.comparingInt(highlight ->
            highlight.range == null ? Integer.MAX_VALUE : highlight.range.begin.line * 1000 + highlight.range.begin.column));
        return highlightList;
    }

    private static List<JavaSyntaxHighlight> collectSyntaxProblemHighlights(List<Problem> problemList) {
        List<JavaSyntaxHighlight> highlightList = new ArrayList<>();
        problemList.forEach(problem -> problem.getLocation().ifPresent(problemLocation ->
            highlightList.add(new JavaSyntaxHighlight(problemLocation.toRange().orElse(null), "problem", false))));
        return highlightList;
    }

    private StyleSpans<Collection<String>> computeFail(String content, List<Problem> problemList) {
        return createStyleSpans(content, collectSyntaxProblemHighlights(problemList));
    }

    private static StyleSpans<Collection<String>> createStyleSpans(String text, List<JavaSyntaxHighlight> highlightList) {
        return createStyleSpans(text, highlightList, null, text);
    }

    private static StyleSpans<Collection<String>> createStyleSpans(String text, List<JavaSyntaxHighlight> highlightList,
                                                                   List<JavaTextEditSegment> editList,
                                                                   String positionText) {
        List<ResolvedHighlight> resolvedHighlightList = new ArrayList<>();
        NavigableSet<Integer> boundarySet = new TreeSet<>();
        boundarySet.add(0);
        boundarySet.add(text.length());

        for (JavaSyntaxHighlight highlight : highlightList) {
            ResolvedHighlight resolvedHighlight = resolveHighlight(text, highlight, editList, positionText);
            if (resolvedHighlight == null) {
                continue;
            }
            resolvedHighlightList.add(resolvedHighlight);
            boundarySet.add(resolvedHighlight.start());
            boundarySet.add(resolvedHighlight.endExclusive());
        }

        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        Integer start = boundarySet.pollFirst();
        while (start != null && !boundarySet.isEmpty()) {
            int endExclusive = boundarySet.first();
            LinkedHashSet<String> styleSet = new LinkedHashSet<>();
            for (ResolvedHighlight highlight : resolvedHighlightList) {
                if (highlight.start() < endExclusive && start < highlight.endExclusive()) {
                    styleSet.add(highlight.styleClass());
                }
            }
            spansBuilder.add(styleSet.isEmpty() ? Collections.emptyList() : new ArrayList<>(styleSet),
                endExclusive - start);
            start = boundarySet.pollFirst();
        }

        return spansBuilder.create();
    }

    private static ResolvedHighlight resolveHighlight(String text, JavaSyntaxHighlight highlight,
                                                      List<JavaTextEditSegment> editList, String positionText) {
        if (highlight.range == null) {
            return null;
        }

        Integer start = highlight.fromOldText ?
            mapOldOffsetToNewOffset(JavaUtil.getPosition(highlight.range.begin, positionText), editList) :
            Integer.valueOf(JavaUtil.getPosition(highlight.range.begin, text));
        Integer endExclusive = highlight.fromOldText ?
            mapOldOffsetToNewOffset(JavaUtil.getPosition(highlight.range.end, positionText) + 1, editList) :
            Integer.valueOf(JavaUtil.getPosition(highlight.range.end, text) + 1);
        if (start == null || endExclusive == null) {
            return null;
        }

        start = Math.clamp(start, 0, text.length());
        endExclusive = Math.clamp(endExclusive, start, text.length());
        if (start.equals(endExclusive)) {
            return null;
        }
        return new ResolvedHighlight(start, endExclusive, highlight.styleClass);
    }

    private static Integer mapOldOffsetToNewOffset(int oldOffset, List<JavaTextEditSegment> editList) {
        if (editList == null || editList.isEmpty()) {
            return oldOffset;
        }
        int delta = 0;
        for (JavaTextEditSegment edit : editList) {
            if (oldOffset < edit.getOldStart()) {
                break;
            }
            if (oldOffset <= edit.getOldEnd()) {
                if (edit.getOldStart() == edit.getOldEnd()) {
                    delta += edit.getNewEnd() - edit.getNewStart();
                    continue;
                }
                if (oldOffset == edit.getOldEnd()) {
                    delta += (edit.getNewEnd() - edit.getNewStart()) - (edit.getOldEnd() - edit.getOldStart());
                    continue;
                }
                return null;
            }
            delta += (edit.getNewEnd() - edit.getNewStart()) - (edit.getOldEnd() - edit.getOldStart());
        }
        return oldOffset + delta;
    }

    private static JavaTextEditSegment createTextEditSegment(EditRange editRange) {
        return new JavaTextEditSegment(editRange.start(), editRange.oldEnd(), editRange.start(),
            editRange.start() + (editRange.oldEnd() - editRange.start()) + editRange.delta());
    }

    private static EditRange createEditRange(String oldText, String newText) {
        int prefix = 0;
        int maxPrefix = Math.min(oldText.length(), newText.length());
        while (prefix < maxPrefix && oldText.charAt(prefix) == newText.charAt(prefix)) {
            prefix++;
        }

        int oldSuffix = oldText.length();
        int newSuffix = newText.length();
        while (oldSuffix > prefix &&
               newSuffix > prefix && oldText.charAt(oldSuffix - 1) == newText.charAt(newSuffix - 1)) {
            oldSuffix--;
            newSuffix--;
        }
        return new EditRange(prefix, oldSuffix, newSuffix - oldSuffix);
    }

    private record EditRange(int start, int oldEnd, int delta) {
    }

    private record ResolvedHighlight(int start, int endExclusive, String styleClass) {
    }
}
