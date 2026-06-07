package org.a8043.simpleIDE.fileEditor.javaFile;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.StrUtil;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.project.index.Access;
import org.a8043.simpleIDE.project.index.FieldSignature;
import org.a8043.simpleIDE.project.index.IndexPoint;
import org.a8043.simpleIDE.project.index.MethodSignature;
import org.a8043.simpleIDE.util.JavaUtil;

import java.util.*;
import java.util.function.Supplier;

public class JavaTypeResolver {
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

    private final ProjectEditor editor;
    private final JavaFileState state;
    private final Supplier<String> contentSupplier;

    public JavaTypeResolver(ProjectEditor editor, JavaFileState state, Supplier<String> contentSupplier) {
        this.editor = editor;
        this.state = state;
        this.contentSupplier = contentSupplier;
    }

    public IndexPoint resolveExpressionType(Expression expression, CompilationUnit compilationUnit) {
        return switch (expression) {
            case NameExpr nameExpr -> resolveNameExprType(nameExpr, compilationUnit);
            case MethodCallExpr methodCallExpr -> {
                IndexPoint scopeType = methodCallExpr.getScope()
                    .map(scope -> resolveExpressionType(scope, compilationUnit)).orElse(state.getIndexPoint());
                MethodSignature methodSignature = resolveMethodSignature(scopeType, methodCallExpr, compilationUnit);
                yield methodSignature != null ? methodSignature.getReturnType() : null;
            }
            case FieldAccessExpr fieldAccessExpr -> {
                IndexPoint scopeType = resolveExpressionType(fieldAccessExpr.getScope(), compilationUnit);
                if (scopeType == null) {
                    yield null;
                }
                JavaFieldLookup fieldLookup = resolveFieldLookup(scopeType, fieldAccessExpr.getNameAsString());
                FieldSignature field = fieldLookup != null ? fieldLookup.signature() : null;
                yield field != null ? field.getType() : null;
            }
            case ThisExpr ignored -> state.getIndexPoint();
            case SuperExpr ignored -> state.getIndexPoint() != null ? state.getIndexPoint().getParent() : null;
            case EnclosedExpr enclosedExpr -> resolveExpressionType(enclosedExpr.getInner(), compilationUnit);
            case CastExpr castExpr -> resolveType(castExpr.getType().asString(), compilationUnit);
            case ObjectCreationExpr objectCreationExpr ->
                resolveType(objectCreationExpr.getType().asString(), compilationUnit);
            case ArrayCreationExpr arrayCreationExpr -> resolveType(arrayCreationExpr.getElementType().asString() +
                                                                    "[]".repeat(arrayCreationExpr.getLevels().size()), compilationUnit);
            case ArrayAccessExpr arrayAccessExpr -> {
                IndexPoint arrayType = resolveExpressionType(arrayAccessExpr.getName(), compilationUnit);
                yield editor.getIndex().getArrayComponentType(arrayType);
            }
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

    public IndexPoint resolveNameExprType(NameExpr nameExpr, CompilationUnit compilationUnit) {
        String name = nameExpr.getNameAsString();
        Parameter parameter = resolveVisibleParameter(nameExpr);
        if (parameter != null) {
            IndexPoint parameterType = resolveType(parameter.getType().asString(), compilationUnit);
            if (parameterType != null) {
                return parameterType;
            }
        }

        VariableDeclarator variable = resolveVisibleLocalVariable(nameExpr);
        if (variable != null) {
            IndexPoint variableType = resolveType(variable.getType().asString(), compilationUnit);
            if (variableType != null) {
                return variableType;
            }
        }

        if (state.getIndexPoint() != null) {
            IndexPoint currentFileFieldType = state.getIndexPoint().getField(name) != null ?
                state.getIndexPoint().getField(name).getType() : null;
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

    public Parameter resolveVisibleParameter(NameExpr nameExpr) {
        MethodDeclaration method = nameExpr.findAncestor(MethodDeclaration.class).orElse(null);
        if (method == null) {
            return null;
        }
        return method.getParameterByName(nameExpr.getNameAsString()).orElse(null);
    }

    public VariableDeclarator resolveVisibleLocalVariable(NameExpr nameExpr) {
        MethodDeclaration method = nameExpr.findAncestor(MethodDeclaration.class).orElse(null);
        if (method == null) {
            return null;
        }

        int currentPosition = nameExpr.getName().getRange()
            .map(range -> JavaUtil.getPosition(range.begin, getContent()))
            .orElse(Integer.MIN_VALUE);
        return method.findAll(VariableDeclarator.class).stream()
            .filter(declarator -> declarator.getNameAsString().equals(nameExpr.getNameAsString()))
            .filter(declarator -> declarator.findAncestor(FieldDeclaration.class).isEmpty())
            .filter(declarator -> declarator.getName().getRange()
                                      .map(range -> JavaUtil.getPosition(range.begin, getContent()))
                                      .orElse(Integer.MAX_VALUE) <= currentPosition)
            .max(Comparator.comparingInt(declarator -> declarator.getName().getRange()
                .map(range -> JavaUtil.getPosition(range.begin, getContent())).orElse(-1)))
            .orElse(null);
    }

    public IndexPoint resolveType(String typeName, CompilationUnit compilationUnit) {
        return JavaUtil.resolveType(editor.getIndex(), state.getIndexPoint(), typeName, compilationUnit);
    }

    public MethodSignature resolveMethodSignature(IndexPoint scopeType, MethodCallExpr methodCallExpr,
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

    public JavaFieldLookup resolveFieldLookup(IndexPoint startPoint, String fieldName) {
        for (IndexPoint current = startPoint; current != null; current = current.getParent()) {
            FieldSignature fieldSignature = current.getField(fieldName);
            if (fieldSignature == null) {
                fieldSignature = resolveFieldSignatureFromSource(current, fieldName);
            }
            if (fieldSignature != null) {
                return new JavaFieldLookup(current, fieldSignature);
            }
        }
        return null;
    }

    public VariableDeclarator resolveFieldVariable(IndexPoint owner, String fieldName) {
        CompilationUnit sourceCompilationUnit = owner != null ? owner.resolveCompilationUnit() : null;
        if (sourceCompilationUnit == null) {
            sourceCompilationUnit = owner != null ? owner.resolveCompilationUnit() : null;
        }
        if (sourceCompilationUnit == null) {
            return null;
        }
        return sourceCompilationUnit.findAll(VariableDeclarator.class).stream()
            .filter(variable -> variable.findAncestor(FieldDeclaration.class).isPresent())
            .filter(variable -> variable.getNameAsString().equals(fieldName))
            .findFirst().orElse(null);
    }

    public ClassOrInterfaceDeclaration resolveTypeDeclaration(IndexPoint typePoint) {
        CompilationUnit sourceCompilationUnit = typePoint != null ?
            typePoint.resolveCompilationUnit() : null;
        if (sourceCompilationUnit == null) {
            sourceCompilationUnit = typePoint != null ? typePoint.resolveCompilationUnit() : null;
            ;
        }
        if (sourceCompilationUnit == null) {
            return null;
        }
        CompilationUnit finalSourceCompilationUnit = sourceCompilationUnit;
        return finalSourceCompilationUnit.findAll(ClassOrInterfaceDeclaration.class).stream()
            .filter(declaration -> Arrays.equals(JavaUtil.buildTypePath(finalSourceCompilationUnit, declaration),
                typePoint.getPath()))
            .findFirst().orElse(null);
    }

    public MethodDeclaration resolveMethodDeclaration(CompilationUnit sourceCompilationUnit,
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

    public List<Expression> getScopeExpressionList(Expression expression) {
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

    public String formatNodeContext(Node node) {
        MethodDeclaration methodDeclaration = node.findAncestor(MethodDeclaration.class).orElse(null);
        if (methodDeclaration != null && state.getIndexPoint() != null) {
            return formatIndexPointPath(state.getIndexPoint()) + "." + methodDeclaration.getNameAsString();
        }
        return formatIndexPointPath(state.getIndexPoint());
    }

    public String formatIndexPointPath(IndexPoint point) {
        return point != null ? StrUtil.join(".", (Object[]) point.getPath()) : "unknown";
    }

    private FieldSignature resolveFieldSignatureFromSource(IndexPoint owner, String fieldName) {
        if (owner == null) {
            return null;
        }
        CompilationUnit sourceCompilationUnit = owner.resolveCompilationUnit();
        if (sourceCompilationUnit == null) {
            sourceCompilationUnit = owner.resolveCompilationUnit();
        }
        if (sourceCompilationUnit == null) {
            return null;
        }
        VariableDeclarator variable = sourceCompilationUnit.findAll(VariableDeclarator.class).stream()
            .filter(candidate -> candidate.findAncestor(FieldDeclaration.class).isPresent())
            .filter(candidate -> candidate.getNameAsString().equals(fieldName))
            .findFirst().orElse(null);
        if (variable == null) {
            return null;
        }
        FieldDeclaration fieldDeclaration = variable.findAncestor(FieldDeclaration.class).orElse(null);
        if (fieldDeclaration == null) {
            return null;
        }
        IndexPoint fieldType = JavaUtil.resolveType(editor.getIndex(), owner,
            variable.getType().asString(), sourceCompilationUnit);
        return new FieldSignature(fieldName, Access.fromJavaParser(fieldDeclaration.getAccessSpecifier()),
            fieldDeclaration.isStatic(), fieldType);
    }

    private String getContent() {
        return contentSupplier.get();
    }

    private IndexPoint resolveIndexedVariableType(String name) {
        return editor.getIndex().getIndexList().stream()
            .flatMap(point -> point.getMethodList().stream())
            .filter(methodSignature -> methodSignature.getParameterMap() != null)
            .map(methodSignature -> methodSignature.getParameterMap().get(name))
            .filter(Objects::nonNull)
            .findFirst().orElse(null);
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

    private List<IndexPoint> resolveArgumentTypes(NodeList<Expression> argumentList,
                                                  CompilationUnit compilationUnit) {
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
                                Objects.equals(left.getPkg().getModule().getCacheName(),
                                    right.getPkg().getModule().getCacheName()) &&
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
            case "short", "char" -> Set.of("int", "long", "float", "double").contains(expectedName);
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

    public record JavaFieldLookup(IndexPoint owner, FieldSignature signature) {
    }
}
