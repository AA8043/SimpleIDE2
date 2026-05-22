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
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.javadoc.Javadoc;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.project.index.Index;
import org.a8043.simpleIDE.project.index.IndexPoint;
import org.a8043.simpleIDE.project.index.MethodSignature;
import org.a8043.simpleIDE.resource.ResourceManager;
import org.a8043.simpleIDE.util.FileUtil;
import org.a8043.simpleIDE.util.FixedList;
import org.a8043.simpleIDE.util.JavaUtil;
import org.a8043.simpleIDE.util.SearchUtil;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class JavaFile extends FileEditor {
    public static final String STYLE = ResourceUtil.readUtf8Str("styles/JavaHighlighter.css");
    private static final Map<String, String> WRAPPER_TO_PRIMITIVE = Map.of(
        "Boolean", "boolean",
        "Byte", "byte",
        "Short", "short",
        "Character", "char",
        "Integer", "int",
        "Long", "long",
        "Float", "float",
        "Double", "double"
    );
    private final List<ParseResult<CompilationUnit>> parseResultHistoryList = new FixedList<>(10);
    private final List<String> contentHistoryList = new FixedList<>(10);
    private final List<CodeError> semanticErrorList = new ArrayList<>();
    private List<TextEditSegment> pendingHighlightEdits;
    private String pendingHighlightContent;
    private IndexPoint indexPoint;

    public JavaFile(ControllableFile file, ProjectEditor editor) {
        super(file, editor);
        File file1 = file.getFile();
        String relativePath = FileUtil.getRelativePath(
            FileUtil.findFileDirInFolders(editor.getProjectModel().getSrcDirList(), file1.getName()), file1);
        String[] path = relativePath.substring(0, relativePath.length() - ".java".length()).split("/");
        indexPoint = JavaUtil.resolveModuleByPath(getEditor().getIndex(), path)
            .getPackage(ArrayUtil.sub(path, 0, path.length - 1)).getPoints().stream()
            .filter(point -> point.getName().equals(path[path.length - 1])).findFirst().orElse(null);
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

    private HighlightSnapshot getLatestSuccessfulHighlightSnapshot() {
        List<ParseResult<CompilationUnit>> parseHistory = CollUtil.reverseNew(parseResultHistoryList);
        List<String> contentHistory = CollUtil.reverseNew(contentHistoryList);
        for (int i = 0; i < Math.min(parseHistory.size(), contentHistory.size()); i++) {
            ParseResult<CompilationUnit> parseResult = parseHistory.get(i);
            CompilationUnit compilationUnit = parseResult.getResult().orElse(null);
            if (parseResult.isSuccessful() && compilationUnit != null) {
                return new HighlightSnapshot(compilationUnit, contentHistory.get(i));
            }
        }
        return null;
    }

    @Override
    public List<CompleteItem> computeCompletion(int caretPosition) {
        CompilationUnit latestCompilationUnit = getLatestCompilationUnit();
        if (latestCompilationUnit != null && isPositionInString(latestCompilationUnit, caretPosition)) {
            return new ArrayList<>();
        }

        CompletionContext context = createCompletionContext(caretPosition);
        TextCompletionContext textContext = createTextCompletionContext(caretPosition);
        if (context.isMemberCompletion()) {
            List<CompleteItem> memberItems = computeMemberCompletion(textContext, context);
            if (!memberItems.isEmpty()) {
                return memberItems;
            }
        }
        return computeGlobalCompletion(textContext, context);
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

    public CompletionApplyResult applyCompletion(CompleteItem item, int caretPosition) {
        String content = getContent();
        int start = item.getStart();
        String replacedContent = new StringBuilder(content.substring(0, start))
            .append(item.getText())
            .append(content.substring(caretPosition))
            .toString();
        List<TextEditSegment> editList = new ArrayList<>();
        TextEditSegment completionEdit = new TextEditSegment(start, caretPosition, start, start + item.getText().length());
        editList.add(completionEdit);

        String importQualifiedName = item.getImportQualifiedName();
        String newContent = replacedContent;
        if (importQualifiedName != null && !importQualifiedName.isBlank()) {
            CompilationUnit compilationUnit = getLatestSuccessfulCompilationUnit();
            if (compilationUnit != null && shouldAddImport(compilationUnit, importQualifiedName)) {
                ImportInsertion importInsertion = insertImport(replacedContent, compilationUnit, importQualifiedName);
                newContent = importInsertion.getContent();
                editList.add(remapIntermediateEditToOriginal(importInsertion.getEditSegment(), completionEdit));
            }
        }

        editList.sort(Comparator.comparingInt(TextEditSegment::getOldStart)
            .thenComparingInt(TextEditSegment::getOldEnd));
        pendingHighlightEdits = List.copyOf(editList);
        pendingHighlightContent = newContent;
        return new CompletionApplyResult(newContent, start + item.getText().length());
    }

    private TextEditSegment remapIntermediateEditToOriginal(TextEditSegment intermediateEdit, TextEditSegment previousEdit) {
        int oldStart = mapIntermediateOffsetToOriginal(intermediateEdit.getOldStart(), previousEdit);
        int oldEnd = mapIntermediateOffsetToOriginal(intermediateEdit.getOldEnd(), previousEdit);
        return new TextEditSegment(oldStart, oldEnd, intermediateEdit.getNewStart(), intermediateEdit.getNewEnd());
    }

    private int mapIntermediateOffsetToOriginal(int intermediateOffset, TextEditSegment previousEdit) {
        int oldLength = previousEdit.getOldEnd() - previousEdit.getOldStart();
        int newLength = previousEdit.getNewEnd() - previousEdit.getNewStart();
        int delta = newLength - oldLength;
        if (intermediateOffset <= previousEdit.getNewStart()) {
            return intermediateOffset;
        }
        if (intermediateOffset >= previousEdit.getNewEnd()) {
            return intermediateOffset - delta;
        }
        return previousEdit.getOldStart();
    }

    private boolean shouldAddImport(CompilationUnit compilationUnit, String importQualifiedName) {
        String simpleName = importQualifiedName.substring(importQualifiedName.lastIndexOf('.') + 1);
        if (JavaUtil.resolvePointByName(getEditor().getIndex(), indexPoint, simpleName, compilationUnit) != null) {
            return false;
        }
        return compilationUnit.getImports().stream()
            .filter(importDeclaration -> !importDeclaration.isAsterisk())
            .map(ImportDeclaration::getNameAsString)
            .noneMatch(importQualifiedName::equals);
    }

    private ImportInsertion insertImport(String content, CompilationUnit compilationUnit, String importQualifiedName) {
        String importText;
        int insertPosition;
        List<ImportDeclaration> importList = compilationUnit.getImports();
        if (!importList.isEmpty()) {
            ImportDeclaration lastImport = importList.getLast();
            insertPosition = lastImport.getRange().map(range -> getPosition(range.end, content) + 1).orElse(0);
            importText = "\nimport " + importQualifiedName + ";";
        } else {
            Optional<PackageDeclaration> packageDeclaration = compilationUnit.getPackageDeclaration();
            if (packageDeclaration.isPresent()) {
                insertPosition = packageDeclaration.get().getRange().map(range -> getPosition(range.end, content) + 1)
                    .orElse(0);
                importText = "\n\nimport " + importQualifiedName + ";";
            } else {
                insertPosition = 0;
                importText = "import " + importQualifiedName + ";\n\n";
            }
        }
        String newContent = content.substring(0, insertPosition) + importText + content.substring(insertPosition);
        return new ImportInsertion(newContent,
            new TextEditSegment(insertPosition, insertPosition, insertPosition, insertPosition + importText.length()));
    }

    private CompletionContext createCompletionContext(int caretPosition) {
        String content = getContent();
        int keywordStart = caretPosition;
        while (keywordStart > 0 && isCompletionIdentifierChar(content.charAt(keywordStart - 1))) {
            keywordStart--;
        }
        while (keywordStart < caretPosition && Character.isWhitespace(content.charAt(keywordStart))) {
            keywordStart++;
        }
        int scopeStart = keywordStart > 0 && content.charAt(keywordStart - 1) == '.' ? keywordStart - 1 : -1;
        return new CompletionContext(caretPosition, scopeStart,
            keywordStart, content.substring(keywordStart, caretPosition));
    }

    private boolean isCompletionIdentifierChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
    }

    private TextCompletionContext createTextCompletionContext(int caretPosition) {
        String content = getContent();
        ClassTextMembers classMembers = collectCurrentClassMembers(content);
        MethodTextMembers methodMembers = collectCurrentMethodMembers(content, caretPosition);
        return new TextCompletionContext(content, classMembers, methodMembers);
    }

    private List<CompleteItem> computeMemberCompletion(TextCompletionContext textContext, CompletionContext context) {
        ResolvedCompletionScope scope = resolveCompletionScope(textContext, context);
        if (scope == null || scope.getType() == null) {
            return List.of();
        }
        return createMemberCompletionItems(scope.getType(), context, scope.isStaticOnly());
    }

    private List<CompleteItem> computeGlobalCompletion(TextCompletionContext textContext, CompletionContext context) {
        List<CompleteItem> itemList = new ArrayList<>(createVisibleTextCompletionItems(textContext, context));
        List<IndexPoint> searchResult = SearchUtil.search(getEditor().getIndex().getIndexList(),
            IndexPoint::getName, context.getKeyword());
        Set<String> added = new HashSet<>();
        itemList.forEach(item -> added.add(item.getText()));
        ListUtil.sub(searchResult, 0, 100).forEach(indexPoint -> {
            if (added.add(indexPoint.getName())) {
                itemList.add(new CompleteItem(indexPoint, context.getCaretPosition(), context.getReplaceStart()));
            }
        });
        return itemList;
    }

    private ResolvedCompletionScope resolveCompletionScope(TextCompletionContext textContext, CompletionContext context) {
        CompilationUnit compilationUnit = getLatestSuccessfulCompilationUnit();
        String scopeText = extractCompletionScopeText(context.getScopeStart());

        if ("this".equals(scopeText)) {
            return new ResolvedCompletionScope(indexPoint, false);
        }

        TextMember typeMember = textContext.findTypeMember(scopeText);
        if (typeMember != null && typeMember.getTypeName() != null) {
            IndexPoint scopeType = resolveType(typeMember.getTypeName(), compilationUnit);
            if (scopeType != null) {
                return new ResolvedCompletionScope(scopeType, false);
            }
        }

        Expression scopeExpression = compilationUnit != null ?
            findCompletionScopeExpression(compilationUnit, context.getScopeStart()) : null;
        if (scopeExpression == null) {
            scopeExpression = parseCompletionScopeExpression(context.getScopeStart());
        }
        if (scopeExpression != null) {
            ResolvedCompletionScope resolvedExpressionScope = resolveExpressionScope(scopeExpression, compilationUnit);
            if (resolvedExpressionScope != null) {
                return resolvedExpressionScope;
            }
        }

        IndexPoint type = resolveType(scopeText, compilationUnit);
        if (type != null) {
            return new ResolvedCompletionScope(type, true);
        }
        return null;
    }

    private ResolvedCompletionScope resolveExpressionScope(Expression expression, CompilationUnit compilationUnit) {
        return switch (expression) {
            case ThisExpr ignored -> new ResolvedCompletionScope(indexPoint, false);
            case SuperExpr ignored ->
                new ResolvedCompletionScope(indexPoint != null ? indexPoint.getParent() : null, false);
            case NameExpr nameExpr -> {
                TextMember visibleMember = compilationUnit == null ? null : findVisibleMember(nameExpr, compilationUnit);
                if (visibleMember != null && visibleMember.getTypeName() != null) {
                    IndexPoint type = resolveType(visibleMember.getTypeName(), compilationUnit);
                    yield type != null ? new ResolvedCompletionScope(type, false) : null;
                }
                IndexPoint type = resolveType(nameExpr.getNameAsString(), compilationUnit);
                yield type != null ? new ResolvedCompletionScope(type, true) : null;
            }
            case FieldAccessExpr fieldAccessExpr -> {
                IndexPoint type = resolveExpressionType(fieldAccessExpr, compilationUnit);
                yield type != null ? new ResolvedCompletionScope(type, false) : null;
            }
            case MethodCallExpr methodCallExpr -> {
                IndexPoint type = resolveExpressionType(methodCallExpr, compilationUnit);
                yield type != null ? new ResolvedCompletionScope(type, false) : null;
            }
            case EnclosedExpr enclosedExpr -> resolveExpressionScope(enclosedExpr.getInner(), compilationUnit);
            default -> {
                IndexPoint type = resolveExpressionType(expression, compilationUnit);
                yield type != null ? new ResolvedCompletionScope(type, false) : null;
            }
        };
    }

    private TextMember findVisibleMember(NameExpr nameExpr, CompilationUnit compilationUnit) {
        String name = nameExpr.getNameAsString();
        MethodDeclaration method = nameExpr.findAncestor(MethodDeclaration.class).orElse(null);
        if (method != null) {
            TextMember parameter = method.getParameterByName(name)
                .map(parameter1 -> new TextMember(parameter1.getNameAsString(),
                    normalizeTypeName(parameter1.getType().asString()), "variable"))
                .orElse(null);
            if (parameter != null) {
                return parameter;
            }

            VariableDeclarator variable = method.findAll(VariableDeclarator.class).stream()
                .filter(declarator -> declarator.getNameAsString().equals(name))
                .filter(declarator -> {
                    int variablePosition = declarator.getName().getRange()
                        .map(range -> getPosition(range.begin, getContent()))
                        .orElse(Integer.MAX_VALUE);
                    int currentPosition = nameExpr.getName().getRange()
                        .map(range -> getPosition(range.begin, getContent()))
                        .orElse(Integer.MIN_VALUE);
                    return variablePosition <= currentPosition;
                })
                .max(Comparator.comparingInt(declarator -> declarator.getName().getRange()
                    .map(range -> getPosition(range.begin, getContent())).orElse(-1)))
                .orElse(null);
            if (variable != null) {
                return new TextMember(variable.getNameAsString(),
                    normalizeTypeName(variable.getType().asString()), "variable");
            }
        }

        if (indexPoint != null) {
            var field = indexPoint.getField(name);
            if (field != null && field.getType() != null) {
                return new TextMember(field.getName(), field.getType().getName(), "field");
            }
        }
        return null;
    }

    private Expression findCompletionScopeExpression(CompilationUnit compilationUnit, int dotPosition) {
        AtomicReference<Expression> result = new AtomicReference<>();
        compilationUnit.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(FieldAccessExpr n, Void arg) {
                n.getName().getRange().ifPresent(range -> {
                    int start = getPosition(range.begin, getContent());
                    if (start - 1 == dotPosition) {
                        result.set(n.getScope());
                    }
                });
                if (result.get() == null) {
                    super.visit(n, arg);
                }
            }

            @Override
            public void visit(MethodCallExpr n, Void arg) {
                n.getName().getRange().ifPresent(range -> {
                    int start = getPosition(range.begin, getContent());
                    if (start - 1 == dotPosition) {
                        result.set(n.getScope().orElse(null));
                    }
                });
                if (result.get() == null) {
                    super.visit(n, arg);
                }
            }
        }, null);
        return result.get();
    }

    private Expression parseCompletionScopeExpression(int dotPosition) {
        String scopeText = extractCompletionScopeText(dotPosition);
        if (scopeText.isBlank()) {
            return null;
        }
        ParseResult<Expression> parseResult = new JavaParser().parse(ParseStart.EXPRESSION,
            Providers.provider(scopeText));
        return parseResult.getResult().orElse(null);
    }

    private String extractCompletionScopeText(int dotPosition) {
        String content = getContent();
        int end = dotPosition;
        int start = dotPosition - 1;
        int parenthesesDepth = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        while (start >= 0) {
            char ch = content.charAt(start);
            switch (ch) {
                case ')' -> parenthesesDepth++;
                case ']' -> bracketDepth++;
                case '}' -> braceDepth++;
                case '(' -> {
                    if (parenthesesDepth == 0) {
                        return content.substring(start + 1, end).trim();
                    }
                    parenthesesDepth--;
                }
                case '[' -> {
                    if (bracketDepth == 0) {
                        return content.substring(start + 1, end).trim();
                    }
                    bracketDepth--;
                }
                case '{' -> {
                    if (braceDepth == 0) {
                        return content.substring(start + 1, end).trim();
                    }
                    braceDepth--;
                }
                default -> {
                    if (parenthesesDepth == 0 && bracketDepth == 0 && braceDepth == 0 &&
                        isCompletionScopeBoundary(ch)) {
                        return content.substring(start + 1, end).trim();
                    }
                }
            }
            start--;
        }
        return content.substring(0, end).trim();
    }

    private ClassTextMembers collectCurrentClassMembers(String content) {
        List<TextMember> fieldList = new ArrayList<>();
        List<TextMember> methodList = new ArrayList<>();
        Matcher matcher = Pattern.compile("([\\w<>\\[\\],.?]+)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(\\(|[;=])")
            .matcher(content);
        while (matcher.find()) {
            String typeName = matcher.group(1);
            String name = matcher.group(2);
            String tail = matcher.group(3);
            if ("(".equals(tail)) {
                methodList.add(new TextMember(name, null, "method"));
            } else {
                fieldList.add(new TextMember(name, normalizeTypeName(typeName), "field"));
            }
        }
        return new ClassTextMembers(fieldList, methodList);
    }

    private MethodTextMembers collectCurrentMethodMembers(String content, int caretPosition) {
        String beforeCaret = content.substring(0, caretPosition);
        int methodBodyStart = beforeCaret.lastIndexOf('{');
        if (methodBodyStart < 0) {
            return new MethodTextMembers(new ArrayList<>(), new ArrayList<>());
        }

        int signatureStart = findMethodSignatureStart(beforeCaret, methodBodyStart);
        if (signatureStart < 0) {
            return new MethodTextMembers(new ArrayList<>(), new ArrayList<>());
        }

        String signatureText = beforeCaret.substring(signatureStart, methodBodyStart);
        String parameterText = extractMethodParameterText(signatureText);
        return new MethodTextMembers(collectParameters(parameterText),
            collectLocalVariables(beforeCaret.substring(methodBodyStart + 1)));
    }

    private int findMethodSignatureStart(String beforeCaret, int methodBodyStart) {
        for (int i = methodBodyStart - 1; i >= 0; i--) {
            char ch = beforeCaret.charAt(i);
            if (ch == ';' || ch == '}' || ch == '{') {
                return i + 1;
            }
        }
        return 0;
    }

    private String extractMethodParameterText(String signatureText) {
        int parameterStart = signatureText.indexOf('(');
        int parameterEnd = signatureText.lastIndexOf(')');
        if (parameterStart < 0 || parameterEnd <= parameterStart) {
            return "";
        }
        return signatureText.substring(parameterStart + 1, parameterEnd);
    }

    private List<TextMember> collectParameters(String parameterText) {
        List<TextMember> parameterList = new ArrayList<>();
        for (String parameter : splitParameters(parameterText)) {
            String trimmed = parameter.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2) {
                parameterList.add(new TextMember(parts[parts.length - 1],
                    normalizeTypeName(String.join(" ", Arrays.copyOf(parts, parts.length - 1))), "variable"));
            }
        }
        return parameterList;
    }

    private List<String> splitParameters(String parameterText) {
        List<String> parameters = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int genericDepth = 0;
        for (int i = 0; i < parameterText.length(); i++) {
            char ch = parameterText.charAt(i);
            if (ch == '<') {
                genericDepth++;
            } else if (ch == '>') {
                genericDepth = Math.max(0, genericDepth - 1);
            }
            if (ch == ',' && genericDepth == 0) {
                parameters.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        if (!current.isEmpty()) {
            parameters.add(current.toString());
        }
        return parameters;
    }

    private List<TextMember> collectLocalVariables(String methodBodyText) {
        List<TextMember> localVariableList = new ArrayList<>();
        Matcher matcher = Pattern.compile("([\\w<>\\[\\],.?]+)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(=|;)")
            .matcher(methodBodyText);
        while (matcher.find()) {
            localVariableList.add(new TextMember(matcher.group(2), normalizeTypeName(matcher.group(1)), "variable"));
        }
        return localVariableList;
    }

    private String normalizeTypeName(String typeName) {
        return typeName.replace("final ", "").replace("...", "[]").trim();
    }

    private List<CompleteItem> createVisibleTextCompletionItems(TextCompletionContext textContext,
                                                                CompletionContext context) {
        List<TextMember> visibleMembers = new ArrayList<>();
        visibleMembers.addAll(textContext.getClassMembers().getFieldList());
        visibleMembers.addAll(textContext.getClassMembers().getMethodList());
        visibleMembers.addAll(textContext.getMethodMembers().getParameterList());
        visibleMembers.addAll(textContext.getMethodMembers().getLocalVariableList());
        List<TextMember> searchResult = SearchUtil.search(visibleMembers, TextMember::getName, context.getKeyword());
        List<CompleteItem> itemList = new ArrayList<>();
        Set<String> added = new HashSet<>();
        ListUtil.sub(searchResult, 0, 100).forEach(member -> {
            if (added.add(member.getName())) {
                itemList.add(new CompleteItem(member.getKind(), member.getName(),
                    member.getTypeName() != null ? member.getTypeName() : ".",
                    context.getCaretPosition(), context.getReplaceStart(), member.getName()));
            }
        });
        return itemList;
    }

    private List<CompleteItem> createCurrentClassMemberItems(TextCompletionContext textContext, CompletionContext context) {
        List<TextMember> currentClassMembers = new ArrayList<>();
        currentClassMembers.addAll(textContext.getClassMembers().getFieldList());
        currentClassMembers.addAll(textContext.getClassMembers().getMethodList());
        List<TextMember> searchResult = SearchUtil.search(currentClassMembers, TextMember::getName, context.getKeyword());
        List<CompleteItem> itemList = new ArrayList<>();
        Set<String> added = new HashSet<>();
        ListUtil.sub(searchResult, 0, 100).forEach(member -> {
            if (added.add(member.getName())) {
                itemList.add(new CompleteItem(member.getKind(), member.getName(),
                    member.getTypeName() != null ? member.getTypeName() : ".",
                    context.getCaretPosition(), context.getReplaceStart(), member.getName()));
            }
        });
        return itemList;
    }

    private boolean isCompletionScopeBoundary(char ch) {
        return Character.isWhitespace(ch) || ch == ';' || ch == ',' || ch == '=' || ch == '+' || ch == '-' ||
               ch == '*' || ch == '/' || ch == '%' || ch == '&' || ch == '|' || ch == '^' || ch == '!' ||
               ch == '?' || ch == ':' || ch == '<' || ch == '>';
    }

    private IndexPoint resolveExpressionType(Expression expression, CompilationUnit compilationUnit) {
        return switch (expression) {
            case NameExpr nameExpr -> resolveNameExprType(nameExpr, compilationUnit);
            case MethodCallExpr methodCallExpr -> {
                IndexPoint scopeType = methodCallExpr.getScope()
                    .map(scope -> resolveExpressionType(scope, compilationUnit)).orElse(indexPoint);
                MethodSignature methodSignature = resolveMethodSignature(scopeType, methodCallExpr, compilationUnit);
                yield methodSignature != null ? methodSignature.getReturnType() : null;
            }
            case FieldAccessExpr fieldAccessExpr -> {
                IndexPoint scopeType = resolveExpressionType(fieldAccessExpr.getScope(), compilationUnit);
                if (scopeType == null) {
                    yield null;
                }
                var field = scopeType.getField(fieldAccessExpr.getNameAsString());
                yield field != null ? field.getType() : null;
            }
            case ThisExpr ignored -> indexPoint;
            case SuperExpr ignored -> indexPoint != null ? indexPoint.getParent() : null;
            case EnclosedExpr enclosedExpr -> resolveExpressionType(enclosedExpr.getInner(), compilationUnit);
            case CastExpr castExpr -> resolveType(castExpr.getType().asString(), compilationUnit);
            case ObjectCreationExpr objectCreationExpr ->
                resolveType(objectCreationExpr.getType().asString(), compilationUnit);
            case StringLiteralExpr ignored -> resolveType("String", compilationUnit);
            case IntegerLiteralExpr ignored -> resolveType("int", compilationUnit);
            case LongLiteralExpr ignored -> resolveType("long", compilationUnit);
            case DoubleLiteralExpr ignored -> resolveType("double", compilationUnit);
            case BooleanLiteralExpr ignored -> resolveType("boolean", compilationUnit);
            case CharLiteralExpr ignored -> resolveType("char", compilationUnit);
            case UnaryExpr unaryExpr -> resolveExpressionType(unaryExpr.getExpression(), compilationUnit);
            default -> null;
        };
    }

    private IndexPoint resolveNameExprType(NameExpr nameExpr, CompilationUnit compilationUnit) {
        String name = nameExpr.getNameAsString();
        MethodDeclaration method = nameExpr.findAncestor(MethodDeclaration.class).orElse(null);
        if (method != null) {
            IndexPoint parameterType = method.getParameterByName(name)
                .map(parameter -> resolveType(parameter.getType().asString(), compilationUnit))
                .orElse(null);
            if (parameterType != null) {
                return parameterType;
            }

            Optional<VariableDeclarator> variable = method.findAll(VariableDeclarator.class).stream()
                .filter(declarator -> declarator.getNameAsString().equals(name))
                .filter(declarator -> {
                    int variablePosition = declarator.getName().getRange()
                        .map(range -> getPosition(range.begin, getContent()))
                        .orElse(Integer.MAX_VALUE);
                    int currentPosition = nameExpr.getName().getRange()
                        .map(range -> getPosition(range.begin, getContent()))
                        .orElse(Integer.MIN_VALUE);
                    return variablePosition <= currentPosition;
                })
                .max(Comparator.comparingInt(declarator -> declarator.getName().getRange()
                    .map(range -> getPosition(range.begin, getContent())).orElse(-1)));
            if (variable.isPresent()) {
                IndexPoint variableType = resolveType(variable.get().getType().asString(), compilationUnit);
                if (variableType != null) {
                    return variableType;
                }
            }
        }

        if (indexPoint != null) {
            IndexPoint currentFileFieldType = indexPoint.getField(name) != null ? indexPoint.getField(name).getType() : null;
            if (currentFileFieldType != null) {
                return currentFileFieldType;
            }
        }

        IndexPoint indexedType = resolveIndexedVariableType(name);
        if (indexedType != null) {
            return indexedType;
        }

        return resolveType(name, compilationUnit);
    }

    private IndexPoint resolveIndexedVariableType(String name) {
        return getEditor().getIndex().getIndexList().stream()
            .flatMap(point -> point.getMethodList().stream())
            .filter(methodSignature -> methodSignature.getParameterMap() != null)
            .map(methodSignature -> methodSignature.getParameterMap().get(name))
            .filter(Objects::nonNull)
            .findFirst().orElse(null);
    }

    private IndexPoint resolveType(String typeName, CompilationUnit compilationUnit) {
        String normalizedTypeName = typeName.replace("[]", "");
        return JavaUtil.resolvePointByName(getEditor().getIndex(), indexPoint, normalizedTypeName, compilationUnit);
    }

    private MethodSignature resolveMethodSignature(IndexPoint scopeType, MethodCallExpr methodCallExpr,
                                                   CompilationUnit compilationUnit) {
        if (scopeType == null) {
            return null;
        }
        List<MethodSignature> methodList = scopeType.getMethodList(methodCallExpr.getNameAsString()).stream()
            .filter(Objects::nonNull).toList();
        if (methodList.isEmpty()) {
            return null;
        }
        if (methodList.size() == 1) {
            return methodList.getFirst();
        }

        List<IndexPoint> argumentTypeList = resolveArgumentTypes(methodCallExpr.getArguments(), compilationUnit);
        List<MethodSignature> sameParameterCountMethodList = methodList.stream()
            .filter(methodSignature -> methodSignature.getParameterCount() == argumentTypeList.size())
            .toList();
        if (sameParameterCountMethodList.size() == 1) {
            return sameParameterCountMethodList.getFirst();
        }

        MethodSignature methodSignature = selectBestMethodSignature(sameParameterCountMethodList.isEmpty() ?
            methodList : sameParameterCountMethodList, argumentTypeList);
        if (methodSignature != null) {
            return methodSignature;
        }
        return sameParameterCountMethodList.isEmpty() ? methodList.getFirst() : sameParameterCountMethodList.getFirst();
    }

    private MethodSignature selectBestMethodSignature(List<MethodSignature> methodList, List<IndexPoint> argumentTypeList) {
        MethodSignature bestMethod = null;
        int bestScore = Integer.MIN_VALUE;
        for (MethodSignature methodSignature : methodList) {
            int score = scoreMethodMatch(methodSignature.getParameterTypeList(), argumentTypeList);
            if (score > bestScore) {
                bestMethod = methodSignature;
                bestScore = score;
            }
        }
        return bestScore >= 0 ? bestMethod : null;
    }

    private List<IndexPoint> resolveArgumentTypes(List<Expression> argumentList, CompilationUnit compilationUnit) {
        return argumentList.stream().map(argument -> resolveExpressionType(argument, compilationUnit)).toList();
    }

    private int scoreMethodMatch(List<IndexPoint> parameterTypeList, List<IndexPoint> argumentTypeList) {
        if (parameterTypeList.size() != argumentTypeList.size()) {
            return -1;
        }
        int score = 0;
        for (int i = 0; i < parameterTypeList.size(); i++) {
            IndexPoint parameterType = parameterTypeList.get(i);
            IndexPoint argumentType = argumentTypeList.get(i);
            if (parameterType == null || argumentType == null) {
                continue;
            }
            if (isSameType(argumentType, parameterType)) {
                score += 100;
                continue;
            }
            if (isBoxingCompatible(argumentType, parameterType)) {
                score += 90;
                continue;
            }
            if (isPrimitiveWideningCompatible(argumentType, parameterType)) {
                score += 80;
                continue;
            }
            if (isAssignableType(argumentType, parameterType)) {
                score += 70;
                continue;
            }
            return -1;
        }
        return score;
    }

    private boolean isAssignableType(IndexPoint actualType, IndexPoint expectedType) {
        for (IndexPoint current = actualType; current != null; current = current.getParent()) {
            if (isSameType(current, expectedType)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSameType(IndexPoint left, IndexPoint right) {
        return left == right || left != null && right != null &&
                                Objects.equals(left.getPkg().getModule().getCacheName(), right.getPkg().getModule().getCacheName()) &&
                                Arrays.equals(left.getPath(), right.getPath());
    }

    private boolean isBoxingCompatible(IndexPoint actualType, IndexPoint expectedType) {
        String actualName = normalizePrimitiveName(actualType);
        String expectedName = normalizePrimitiveName(expectedType);
        return actualName != null && actualName.equals(expectedName) &&
               !Objects.equals(actualType.getName(), expectedType.getName());
    }

    private boolean isPrimitiveWideningCompatible(IndexPoint actualType, IndexPoint expectedType) {
        String actualName = normalizePrimitiveName(actualType);
        String expectedName = normalizePrimitiveName(expectedType);
        if (actualName == null || expectedName == null || actualName.equals(expectedName)) {
            return false;
        }
        return switch (actualName) {
            case "byte" -> Set.of("short", "int", "long", "float", "double").contains(expectedName);
            case "short" -> Set.of("int", "long", "float", "double").contains(expectedName);
            case "char" -> Set.of("int", "long", "float", "double").contains(expectedName);
            case "int" -> Set.of("long", "float", "double").contains(expectedName);
            case "long" -> Set.of("float", "double").contains(expectedName);
            case "float" -> "double".equals(expectedName);
            default -> false;
        };
    }

    private String normalizePrimitiveName(IndexPoint type) {
        if (type == null) {
            return null;
        }
        return WRAPPER_TO_PRIMITIVE.getOrDefault(type.getName(), type.getName());
    }

    private void addMemberCompletionItems(List<CompleteItem> itemList, IndexPoint scopeType,
                                          int caretPosition, int start, String keyword,
                                          boolean staticOnly) {
        List<MemberCompletion> memberList = new ArrayList<>();
        scopeType.getFieldList().stream()
            .filter(field -> !staticOnly || field.isStatic())
            .forEach(field -> memberList.add(new MemberCompletion(field.getName(),
                new CompleteItem("field", field.getName(),
                    field.getType() != null ? StrUtil.join(".", (Object[]) field.getType().getPath()) : "void",
                    caretPosition, start, field.getName()))));
        scopeType.getMethodList().stream()
            .filter(method -> !staticOnly || method.isStatic())
            .forEach(method -> memberList.add(new MemberCompletion(method.getName(),
                new CompleteItem("method", method.getName(), method.getReturnType() != null ?
                    StrUtil.join(".", (Object[]) method.getReturnType().getPath()) : "void",
                    caretPosition, start, method.getName()))));

        List<MemberCompletion> searchResult = SearchUtil.search(memberList, MemberCompletion::getKeyword, keyword);
        Set<String> added = new HashSet<>();
        ListUtil.sub(searchResult, 0, 100).forEach(member -> {
            if (added.add(member.getKeyword())) {
                itemList.add(member.getItem());
            }
        });
    }

    private List<CompleteItem> createMemberCompletionItems(IndexPoint scopeType, CompletionContext context,
                                                           boolean staticOnly) {
        List<CompleteItem> itemList = new ArrayList<>();
        addMemberCompletionItems(itemList, scopeType,
            context.getCaretPosition(), context.getReplaceStart(), context.getKeyword(), staticOnly);
        return itemList;
    }

    @AllArgsConstructor
    @Getter
    private static class ResolvedCompletionScope {
        private final IndexPoint type;
        private final boolean staticOnly;
    }

    @AllArgsConstructor
    @Getter
    private static class CompletionContext {
        private final int caretPosition;
        private final int scopeStart;
        private final int replaceStart;
        private final String keyword;

        private boolean isMemberCompletion() {
            return scopeStart >= 0;
        }
    }

    @AllArgsConstructor
    @Getter
    private static class TextCompletionContext {
        private final String content;
        private final ClassTextMembers classMembers;
        private final MethodTextMembers methodMembers;

        private TextMember findTypeMember(String name) {
            return Stream.concat(methodMembers.getLocalVariableList().stream(),
                    Stream.concat(methodMembers.getParameterList().stream(), classMembers.getFieldList().stream()))
                .filter(member -> member.getName().equals(name))
                .findFirst().orElse(null);
        }
    }

    @AllArgsConstructor
    @Getter
    private static class ClassTextMembers {
        private final List<TextMember> fieldList;
        private final List<TextMember> methodList;
    }

    @AllArgsConstructor
    @Getter
    private static class MethodTextMembers {
        private final List<TextMember> parameterList;
        private final List<TextMember> localVariableList;
    }

    @AllArgsConstructor
    @Getter
    private static class TextMember {
        private final String name;
        private final String typeName;
        private final String kind;
    }

    @AllArgsConstructor
    @Getter
    private static class MemberCompletion {
        private final String keyword;
        private final CompleteItem item;
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
        String content = getContent();
        ParseResult<CompilationUnit> parseResult = new JavaParser().parse(content);
        parseResultHistoryList.add(parseResult);
        contentHistoryList.add(content);
        indexPoint = getEditor().getIndex().index(indexPoint.getPkg(), indexPoint.getName(), content);
        semanticErrorList.clear();

        CompilationUnit unit = parseResult.getResult().orElse(null);
        if (parseResult.isSuccessful()) {
            pendingHighlightEdits = null;
            pendingHighlightContent = null;
        }
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
        String content = getContent();
        ParseResult<CompilationUnit> parseResult = getLatestParseResult();
        if (parseResult.isSuccessful()) {
            CompilationUnit compilationUnit = parseResult.getResult().orElse(null);
            if (compilationUnit != null) {
                return computeSuccess(compilationUnit, content);
            }
        }

        HighlightSnapshot snapshot = getLatestSuccessfulHighlightSnapshot();
        if (snapshot != null) {
            List<SyntaxHighlight> highlightList = new ArrayList<>(collectSyntaxHighlights(snapshot.getCompilationUnit()));
            highlightList.addAll(collectProblemHighlights(parseResult.getProblems()));
            if (pendingHighlightEdits != null && Objects.equals(pendingHighlightContent, content)) {
                return createStyleSpans(content, highlightList, pendingHighlightEdits, snapshot.getContent());
            }
            return createStyleSpans(content, highlightList,
                List.of(createTextEditSegment(createEditRange(snapshot.getContent(), content))), snapshot.getContent());
        }
        return computeFail(parseResult.getProblems(), content);
    }

    private static StyleSpans<Collection<String>> computeSuccess(CompilationUnit cu, String text) {
        return createStyleSpans(text, collectSyntaxHighlights(cu));
    }

    private static List<SyntaxHighlight> collectSyntaxHighlights(CompilationUnit cu) {
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
        return highlightList;
    }

    private static List<SyntaxHighlight> collectProblemHighlights(List<Problem> problemList) {
        List<SyntaxHighlight> highlightList = new ArrayList<>();
        problemList.forEach(problem -> problem.getLocation().ifPresent(problemLocation ->
            highlightList.add(new SyntaxHighlight(problemLocation.toRange().orElse(null), "problem", false))));
        return highlightList;
    }

    private static StyleSpans<Collection<String>> computeFail(List<Problem> problemList, String text) {
        return createStyleSpans(text, collectProblemHighlights(problemList));
    }

    private static StyleSpans<Collection<String>> createStyleSpans(String text, List<SyntaxHighlight> highlightList) {
        return createStyleSpans(text, highlightList, null, text);
    }

    private static StyleSpans<Collection<String>> createStyleSpans(String text, List<SyntaxHighlight> highlightList,
                                                                   List<TextEditSegment> editList,
                                                                   String positionText) {
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        int lastPosition = 0;

        for (SyntaxHighlight highlight : highlightList) {
            if (highlight.range == null) {
                continue;
            }

            Integer start = highlight.fromOldText ?
                mapOldOffsetToNewOffset(getPosition(highlight.range.begin, positionText), editList) :
                getPosition(highlight.range.begin, text);
            Integer endExclusive = highlight.fromOldText ?
                mapOldOffsetToNewOffset(getPosition(highlight.range.end, positionText) + 1, editList) :
                getPosition(highlight.range.end, text) + 1;
            if (start == null || endExclusive == null) {
                continue;
            }

            start = Math.max(0, Math.min(start, text.length()));
            endExclusive = Math.max(start, Math.min(endExclusive, text.length()));

            if (start > lastPosition) {
                spansBuilder.add(Collections.emptyList(), start - lastPosition);
            }

            spansBuilder.add(Collections.singleton(highlight.styleClass), endExclusive - start);
            lastPosition = endExclusive;
        }

        if (lastPosition < text.length()) {
            spansBuilder.add(Collections.emptyList(), text.length() - lastPosition);
        }

        return spansBuilder.create();
    }

    private static Integer mapOldOffsetToNewOffset(int oldOffset, List<TextEditSegment> editList) {
        if (editList == null || editList.isEmpty()) {
            return oldOffset;
        }
        int delta = 0;
        for (TextEditSegment edit : editList) {
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

    private static TextEditSegment createTextEditSegment(EditRange editRange) {
        return new TextEditSegment(editRange.start(), editRange.oldEnd(), editRange.start(),
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
        while (oldSuffix > prefix && newSuffix > prefix && oldText.charAt(oldSuffix - 1) == newText.charAt(newSuffix - 1)) {
            oldSuffix--;
            newSuffix--;
        }
        return new EditRange(prefix, oldSuffix, newSuffix - oldSuffix);
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

    @AllArgsConstructor
    @Getter
    public static class CompletionApplyResult {
        private final String content;
        private final int caretPosition;
    }

    @AllArgsConstructor
    @Getter
    private static class ImportInsertion {
        private final String content;
        private final TextEditSegment editSegment;
    }

    @AllArgsConstructor
    @Getter
    private static class HighlightSnapshot {
        private final CompilationUnit compilationUnit;
        private final String content;
    }

    @AllArgsConstructor
    @Getter
    private static class TextEditSegment {
        private final int oldStart;
        private final int oldEnd;
        private final int newStart;
        private final int newEnd;
    }

    private record EditRange(int start, int oldEnd, int delta) {
    }

    private static class SyntaxHighlight {
        private final Range range;
        private final String styleClass;
        private final boolean fromOldText;

        private SyntaxHighlight(Range range, String styleClass) {
            this(range, styleClass, true);
        }

        private SyntaxHighlight(Range range, String styleClass, boolean fromOldText) {
            this.range = range;
            this.styleClass = styleClass;
            this.fromOldText = fromOldText;
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
                            MethodSignature methodSignature1 = resolveMethodSignature(lastPoint, methodCallExpr,
                                compilationUnit);
                            if (methodSignature1 == null) {
                                yield null;
                            }
                            methodSignature.set(methodSignature1);
                            yield methodSignature1.getReturnType();
                        }
                        case FieldAccessExpr fieldAccessExpr -> {
                            if (lastPoint == null) {
                                yield null;
                            }
                            var field = lastPoint.getField(fieldAccessExpr.getNameAsString());
                            yield field != null ? field.getType() : null;
                        }
                        case NameExpr nameExpr -> resolveExpressionType(nameExpr, compilationUnit);
                        case ThisExpr ignored -> lastPoint != null ? lastPoint : indexPoint;
                        case SuperExpr ignored -> lastPoint != null ? lastPoint.getParent() :
                            indexPoint != null ? indexPoint.getParent() : null;
                        case EnclosedExpr enclosedExpr ->
                            resolveExpressionType(enclosedExpr.getInner(), compilationUnit);
                        default -> resolveExpressionType(expr, compilationUnit);
                    });
                }
                super.visit(n, arg);

                IndexPoint in = lastPointList.get(lastPointList.size() - 2);
                source.set(in);
                CompilationUnit unit = in != null ? index.getCompilationUnit(in) : null;
                if (unit != null) {
                    methodDeclaration.set(resolveMethodDeclaration(unit, n, compilationUnit));
                }
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
            methodSignature.get().getParameterMap().forEach((name, type) -> sb.append("    ")
                .append(StrUtil.join(".", (Object[]) type.getPath())).append(" ").append(name).append(",\n"));
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

    private MethodDeclaration resolveMethodDeclaration(CompilationUnit sourceCompilationUnit,
                                                       MethodCallExpr methodCallExpr,
                                                       CompilationUnit currentCompilationUnit) {
        List<MethodDeclaration> methodList = sourceCompilationUnit.findAll(MethodDeclaration.class).stream()
            .filter(method -> method.getName().equals(methodCallExpr.getName()))
            .toList();
        if (methodList.isEmpty()) {
            return null;
        }
        if (methodList.size() == 1) {
            return methodList.getFirst();
        }

        List<IndexPoint> argumentTypeList = resolveArgumentTypes(methodCallExpr.getArguments(), currentCompilationUnit);
        List<MethodDeclaration> sameParameterCountMethodList = methodList.stream()
            .filter(method -> method.getParameters().size() == argumentTypeList.size())
            .toList();
        if (sameParameterCountMethodList.size() == 1) {
            return sameParameterCountMethodList.getFirst();
        }

        MethodDeclaration bestMethod = null;
        int bestScore = Integer.MIN_VALUE;
        for (MethodDeclaration declaration : sameParameterCountMethodList.isEmpty() ?
            methodList : sameParameterCountMethodList) {
            List<IndexPoint> parameterTypeList = declaration.getParameters().stream()
                .map(parameter -> resolveType(parameter.getType().asString(), sourceCompilationUnit))
                .toList();
            int score = scoreMethodMatch(parameterTypeList, argumentTypeList);
            if (score > bestScore) {
                bestMethod = declaration;
                bestScore = score;
            }
        }
        if (bestScore >= 0) {
            return bestMethod;
        }
        return sameParameterCountMethodList.isEmpty() ? methodList.getFirst() : sameParameterCountMethodList.getFirst();
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
