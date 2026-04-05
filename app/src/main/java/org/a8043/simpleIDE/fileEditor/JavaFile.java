package org.a8043.simpleIDE.fileEditor;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.github.javaparser.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.javadoc.Javadoc;
import lombok.AllArgsConstructor;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.project.index.Index;
import org.a8043.simpleIDE.project.index.IndexPoint;
import org.a8043.simpleIDE.project.index.MethodSignature;
import org.a8043.simpleIDE.resource.ResourceManager;
import org.a8043.simpleIDE.util.FixedList;
import org.a8043.simpleIDE.util.JavaUtil;
import org.a8043.simpleIDE.util.SearchUtil;
import org.a8043.simpleIDE.util.Util;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class JavaFile extends FileEditor {
    public static final String STYLE = ResourceUtil.readUtf8Str("styles/JavaHighlighter.css");
    private final List<ParseResult<CompilationUnit>> parseResultHistoryList = new FixedList<>(10);
    private final List<CodeError> semanticErrorList = new ArrayList<>();
    private IndexPoint indexPoint;

    public JavaFile(ControllableFile file, ProjectEditor editor) {
        super(file, editor);
        // TODO: 模块、test目录
        String relativePath = Util.getRelativePath(getEditor().getConfig().getSrcDir(), file.getFile());
        String[] path = relativePath.substring(0, relativePath.length() - ".java".length()).split("/");
        indexPoint = JavaUtil.resolveModuleByPath(getEditor().getIndex(), indexPoint, path)
            .getPackage(ArrayUtil.sub(path, 0, path.length - 1)).getPoints().stream()
            .filter(point -> point.getName().equals(path[path.length - 1])).findFirst().orElse(null);
    }

    public ParseResult<CompilationUnit> getLatestParseResult() {
        return parseResultHistoryList.getLast();
    }

    public CompilationUnit getLatestCompilationUnit() {
        AtomicReference<CompilationUnit> result = new AtomicReference<>();
        CollUtil.reverseNew(parseResultHistoryList).forEach(parseResult -> {
            if (result.get() == null) {
                parseResult.ifSuccessful(result::set);
            }
        });
        return result.get();
    }

    @Override
    public List<CompleteItem> computeCompletion(int caretPosition) {
        List<CompleteItem> itemList = new ArrayList<>();
        CompilationUnit compilationUnit = getLatestCompilationUnit();
        if (compilationUnit != null && !isPositionInString(compilationUnit, caretPosition)) {
            // TODO: 计算补全
            String keyword = getContent().substring(findLastChar(caretPosition, '.', ';') + 1, caretPosition);
            List<IndexPoint> searchResult = SearchUtil.search(getEditor().getIndex().getIndexList(),
                IndexPoint::getName, keyword);
            ListUtil.sub(searchResult, 0, 100).forEach(indexPoint -> {
                itemList.add(new CompleteItem(indexPoint, caretPosition, caretPosition));
            });
        }
        return itemList;
    }

    private int findLastChar(int position, char... c) {
        String content = getContent();
        for (int i = position - 1; i >= 0; i--) {
            for (char ch : c) {
                if (content.charAt(i) == ch) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isPositionInString(CompilationUnit compilationUnit, int position) {
        List<IntRange> stringRanges = new ArrayList<>();
        VoidVisitorAdapter<Void> collector = new VoidVisitorAdapter<>() {
            @Override
            public void visit(StringLiteralExpr n, Void arg) {
                Range range = n.getRange().orElse(null);
                if (range != null) {
                    int start = range.begin.column + 1;
                    int end = range.end.column - 1;
                    if (position >= start && position <= end) {
                        stringRanges.add(new IntRange(start, end));
                    }
                }
                super.visit(n, arg);
            }
        };
        compilationUnit.accept(collector, null);

        AtomicBoolean result = new AtomicBoolean();
        stringRanges.forEach(range -> {
            if (position >= range.start && position <= range.end) {
                result.set(true);
            }
        });
        return result.get();
    }

    @AllArgsConstructor
    private static class IntRange {
        private final int start;
        private final int end;
    }

    @Override
    protected void onContentChanged() {
        ParseResult<CompilationUnit> parseResult = new JavaParser().parse(getContent());
        parseResultHistoryList.add(parseResult);
        indexPoint = getEditor().getIndex().index(indexPoint.getPkg(), indexPoint.getName(), getContent());
        semanticErrorList.clear();

        CompilationUnit unit = parseResult.getResult().orElse(null);
        if (unit == null) {
            return;
        }

        Function<List<Modifier>, Void> duplicateModifiersErrorAdder = list -> {
            Set<Modifier.Keyword> modifierSet = new HashSet<>();
            list.forEach(modifier -> {
                if (modifierSet.contains(modifier.getKeyword())) {
                    Range range = modifier.getRange().orElse(null);
                    if (range != null) {
                        int start = getPosition(range.begin, getContent());
                        int end = getPosition(range.end, getContent()) + 1;
                        semanticErrorList.add(new CodeError(start, end,
                            ResourceManager.getText("semanticError.duplicateModifiers", modifier.getKeyword()),
                            CodeError.Type.SEMANTIC_ERROR));
                    }
                } else {
                    modifierSet.add(modifier.getKeyword());
                }
            });
            return null;
        };
        unit.findAll(MethodDeclaration.class).forEach(method ->
            duplicateModifiersErrorAdder.apply(method.getModifiers()));
        unit.findAll(ClassOrInterfaceDeclaration.class).forEach(type ->
            duplicateModifiersErrorAdder.apply(type.getModifiers()));
        unit.findAll(FieldDeclaration.class).forEach(field ->
            duplicateModifiersErrorAdder.apply(field.getModifiers()));
    }

    @Override
    public String getHighlightingStyle() {
        return STYLE;
    }

    @Override
    protected StyleSpans<Collection<String>> doComputeHighlighting() {
        ParseResult<CompilationUnit> parseResult = getLatestParseResult();
        AtomicReference<StyleSpans<Collection<String>>> result = new AtomicReference<>();
        if (parseResult.isSuccessful()) {
            parseResult.getResult().ifPresent(cu -> result.set(computeSuccess(cu, getContent())));
        } else {
            result.set(computeFail(parseResult.getProblems(), getContent()));
        }
        return result.get();
    }

    private static StyleSpans<Collection<String>> computeSuccess(CompilationUnit cu, String text) {
        List<SyntaxHighlight> highlightList = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                n.getName().getRange().ifPresent(range ->
                    highlightList.add(new SyntaxHighlight(range, "class-name")));
                super.visit(n, arg);
            }

            @Override
            public void visit(MethodDeclaration n, Void arg) {
                n.getName().getRange().ifPresent(range ->
                    highlightList.add(new SyntaxHighlight(range, "method-name")));
                super.visit(n, arg);
            }

            @Override
            public void visit(StringLiteralExpr n, Void arg) {
                highlightList.add(new SyntaxHighlight(n.getRange().orElse(null), "string-literal"));
                super.visit(n, arg);
            }

            @Override
            public void visit(LineComment n, Void arg) {
                highlightList.add(new SyntaxHighlight(n.getRange().orElse(null), "comment"));
                super.visit(n, arg);
            }

            @Override
            public void visit(BlockComment n, Void arg) {
                highlightList.add(new SyntaxHighlight(n.getRange().orElse(null), "comment"));
                super.visit(n, arg);
            }

            @Override
            public void visit(ClassOrInterfaceType n, Void arg) {
                highlightList.add(new SyntaxHighlight(n.getRange().orElse(null), "var-type"));
                super.visit(n, arg);
            }

            @Override
            public void visit(Modifier n, Void arg) {
                highlightList.add(new SyntaxHighlight(n.getRange().orElse(null), "keyword"));
                super.visit(n, arg);
            }

            @Override
            public void visit(ImportDeclaration n, Void arg) {
                n.getTokenRange().ifPresent(tokenRange -> {
                    JavaToken begin = tokenRange.getBegin();
                    highlightList.add(new SyntaxHighlight(
                        begin.getRange().orElse(null), "keyword"));
                    if (n.isStatic()) {
                        begin.getNextToken().ifPresent(nextToken -> highlightList.add(new SyntaxHighlight(
                            nextToken.getRange().orElse(null), "keyword")));
                    }
                });
                super.visit(n, arg);
            }
        }, null);

        highlightList.sort(Comparator.comparing(h -> h.range.begin.line * 1000 + h.range.begin.column));
        return createStyleSpans(text, highlightList);
    }

    private static StyleSpans<Collection<String>> computeFail(List<Problem> problemList, String text) {
        List<SyntaxHighlight> highlightList = new ArrayList<>();
        problemList.forEach(problem -> problem.getLocation().ifPresent(problemLocation ->
            highlightList.add(new SyntaxHighlight(
                problemLocation.toRange().orElse(null), "problem"))));
        return createStyleSpans(text, highlightList);
    }

    private static StyleSpans<Collection<String>> createStyleSpans(String text, List<SyntaxHighlight> highlightList) {
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        int lastPosition = 0;

        for (SyntaxHighlight highlight : highlightList) {
            if (highlight.range == null) {
                continue;
            }

            int start = getPosition(highlight.range.begin, text);
            int end = getPosition(highlight.range.end, text) + 1;

            if (start > lastPosition) {
                spansBuilder.add(Collections.emptyList(), start - lastPosition);
            }

            spansBuilder.add(Collections.singleton(highlight.styleClass), end - start);
            lastPosition = end;
        }

        if (lastPosition < text.length()) {
            spansBuilder.add(Collections.emptyList(), text.length() - lastPosition);
        }

        return spansBuilder.create();
    }

    private static int getPosition(Position pos, String text) {
        String[] lines = text.split("\n", -1);
        int position = 0;
        for (int i = 0; i < pos.line - 1; i++) {
            position += lines[i].length() + 1;
        }
        position += pos.column - 1;
        return Math.min(position, text.length());
    }

    private static class SyntaxHighlight {
        private final Range range;
        private final String styleClass;

        private SyntaxHighlight(Range range, String styleClass) {
            this.range = range;
            this.styleClass = styleClass;
        }
    }

    @Override
    public String computeHoverTip(int position) {
        CompilationUnit compilationUnit = getLatestCompilationUnit();
        if (compilationUnit == null) {
            return "";
        }

        AtomicReference<MethodDeclaration> methodDeclaration = new AtomicReference<>();
        AtomicReference<MethodSignature> methodSignature = new AtomicReference<>();
        AtomicReference<IndexPoint> source = new AtomicReference<>();
        compilationUnit.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodCallExpr n, Void arg) {
                Range range;
                if ((range = n.getName().getRange().orElse(null)) == null || !isInRange(position, range)) {
                    return;
                }
                Index index = getEditor().getIndex();

                List<IndexPoint> lastPointList = new ArrayList<>(List.of(indexPoint));
                for (Expression expr : getScopeExpressionList(n)) {
                    IndexPoint lastPoint = lastPointList.getLast();
                    lastPointList.add(switch (expr) {
                        case MethodCallExpr methodCallExpr -> {
                            List<MethodSignature> methodList =
                                lastPoint.getMethodList(methodCallExpr.getNameAsString());
                            if (methodList.isEmpty()) {
                                yield null;
                            }
                            MethodSignature methodSignature1 = methodList.getFirst();
                            methodSignature.set(methodSignature1);
                            yield methodSignature1.getReturnType();
                        }
                        case FieldAccessExpr fieldAccessExpr -> {
                            yield lastPoint.getField(fieldAccessExpr.getNameAsString()).getType();
                        }
                        case NameExpr nameExpr -> {
                            String[] classPath = JavaUtil.getClassAbsolutePath(getEditor().getIndex(),
                                lastPoint, nameExpr.getNameAsString(), compilationUnit);
                            yield JavaUtil.resolveModuleByPath(getEditor().getIndex(), lastPoint, classPath)
                                .getPoints().stream()
                                .filter(point -> ArrayUtil.equals(point.getPath(), classPath))
                                .findFirst().orElse(null);
                        }
                        default -> throw new RuntimeException();
                    });
                }
                super.visit(n, arg);

                IndexPoint in = lastPointList.get(lastPointList.size() - 2);
                source.set(in);
                CompilationUnit unit = index.getCompilationUnit(in);
                unit.getTypes().getFirst().ifPresent(type1 -> {
                    // TODO: 同名不同参数的方法
                    List<MethodDeclaration> methodList = type1.findAll(MethodDeclaration.class);
                    if (!methodList.isEmpty()) {
                        MethodDeclaration method = methodList.stream().filter(method1 ->
                            n.getName().equals(method1.getName())).findFirst().orElseThrow();
                        methodDeclaration.set(method);
                    }
                });
            }
        }, null);

        if (methodDeclaration.get() != null && methodSignature.get() != null && source.get() != null) {
            Javadoc javadoc = methodDeclaration.get().getJavadoc().orElse(null);
            if (javadoc == null) {
                return "";
            }

            Map<String, String> paramTagMap = new HashMap<>();
            Map<String, String> otherTagMap = new HashMap<>();
            String description = javadoc.getDescription().toText();

            javadoc.getBlockTags().forEach(tag -> {
                if ("param".equals(tag.getTagName())) {
                    paramTagMap.put(tag.getName().orElse(""), tag.getContent().toText());
                } else {
                    otherTagMap.put(tag.getTagName(), tag.getContent().toText());
                }
            });

            StringBuilder sb = new StringBuilder();
            sb.append("### ").append(StrUtil.join(".", (Object[]) source.get().getPath())).append("\n");
            methodDeclaration.get().getModifiers().stream()
                .map(modifier -> modifier.getKeyword().asString())
                .forEach(modifier -> sb.append(modifier).append(" "));
            sb.append(StrUtil.join(".", (Object[]) methodSignature.get().getReturnType().getPath()))
                .append(" ").append(methodSignature.get().getName()).append(" (\n");
            paramTagMap.keySet().forEach(name -> sb.append("    ").append(name).append("\n"));
            sb.append(")\n\n---\n\n");
            sb.append(description).append("\n");
            otherTagMap.forEach((k, v) -> sb.append("@").append(k).append(" ").append(v).append("\n"));
            if (!paramTagMap.isEmpty()) {
                sb.append("\n---\n\n");
                paramTagMap.forEach((name, desc) -> sb.append(name).append(": ").append(desc).append("\n"));
            }
            return sb.toString();
        }
        return "";
    }

    private List<Expression> getScopeExpressionList(Expression expression) {
        return ListUtil.reverse(getScopeExpressionList0(new ArrayList<>(), expression));
    }

    private List<Expression> getScopeExpressionList0(List<Expression> list, Expression expression) {
        list.add(expression);
        if (expression.isMethodCallExpr()) {
            MethodCallExpr methodCall = expression.asMethodCallExpr();
            methodCall.getScope().ifPresent(scope -> getScopeExpressionList0(list, scope));
        } else if (expression.isFieldAccessExpr()) {
            FieldAccessExpr fieldAccess = expression.asFieldAccessExpr();
            getScopeExpressionList0(list, fieldAccess.getScope());
        }
        return list;
    }

    @Override
    public List<CodeError> getProblemList() {
        ParseResult<CompilationUnit> parseResult = getLatestParseResult();
        List<CodeError> errorList = new ArrayList<>();
        if (!parseResult.isSuccessful()) {
            parseResult.getProblems().forEach(problem -> problem.getLocation().ifPresent(problemLocation -> {
                int start = getPosition(problemLocation.toRange().orElseThrow().begin, getContent());
                int end = getPosition(problemLocation.toRange().orElseThrow().end, getContent()) + 1;
                errorList.add(new CodeError(start, end, problem.getMessage(), CodeError.Type.SYNTAX_ERROR));
            }));
        }
        return errorList;
    }

    private boolean isInRange(int position, Range range) {
        int start = getPosition(range.begin, getContent());
        int end = getPosition(range.end, getContent()) + 1;
        return position >= start && position <= end;
    }
}
