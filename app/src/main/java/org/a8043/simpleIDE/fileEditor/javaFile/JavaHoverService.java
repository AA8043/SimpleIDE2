package org.a8043.simpleIDE.fileEditor.javaFile;

import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.javadoc.Javadoc;
import org.a8043.simpleIDE.fileEditor.ControllableFile;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.project.index.Access;
import org.a8043.simpleIDE.project.index.FieldSignature;
import org.a8043.simpleIDE.project.index.IndexPoint;
import org.a8043.simpleIDE.project.index.MethodSignature;
import org.a8043.simpleIDE.util.JavaUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class JavaHoverService {
    private final ProjectEditor editor;
    private final JavaFileState state;
    private final Supplier<String> contentSupplier;
    private final Supplier<ControllableFile> fileSupplier;
    ;
    private final JavaTypeResolver typeResolver;

    public JavaHoverService(ProjectEditor editor, JavaFileState state, Supplier<String> contentSupplier,
                            Supplier<ControllableFile> fileSupplier, JavaTypeResolver typeResolver) {
        this.editor = editor;
        this.state = state;
        this.contentSupplier = contentSupplier;
        this.fileSupplier = fileSupplier;
        this.typeResolver = typeResolver;
    }

    public String computeHoverTip(int position) {
        CompilationUnit compilationUnit = state.getLatestCompilationUnit();
        if (compilationUnit == null) {
            return "";
        }

        String methodHoverTip = computeMethodHoverTip(position, compilationUnit);
        if (!methodHoverTip.isBlank()) {
            return methodHoverTip;
        }
        return computeSymbolHoverTip(position, compilationUnit);
    }

    public JavaFile.SourceLocation resolveSourceLocation(int position) {
        CompilationUnit compilationUnit = state.getLatestCompilationUnit();
        if (compilationUnit == null) {
            return null;
        }

        AtomicReference<JavaFile.SourceLocation> sourceLocation = new AtomicReference<>();
        compilationUnit.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodDeclaration n, Void arg) {
                if (sourceLocation.get() != null) {
                    return;
                }
                Range range = n.getName().getRange().orElse(null);
                if (range == null || !JavaUtil.isInRange(position, range, getContent())) {
                    super.visit(n, arg);
                    return;
                }
                sourceLocation.set(createLocation(getCurrentFile(), state.getIndexPoint(), n, getContent()));
            }

            @Override
            public void visit(Parameter n, Void arg) {
                if (sourceLocation.get() != null) {
                    return;
                }
                Range range = n.getName().getRange().orElse(null);
                if (range == null || !JavaUtil.isInRange(position, range, getContent())) {
                    super.visit(n, arg);
                    return;
                }
                sourceLocation.set(createLocation(getCurrentFile(), state.getIndexPoint(), n, getContent()));
            }

            @Override
            public void visit(VariableDeclarator n, Void arg) {
                if (sourceLocation.get() != null) {
                    return;
                }
                Range range = n.getName().getRange().orElse(null);
                if (range == null || !JavaUtil.isInRange(position, range, getContent())) {
                    super.visit(n, arg);
                    return;
                }
                sourceLocation.set(createLocation(getCurrentFile(), state.getIndexPoint(), n, getContent()));
            }

            @Override
            public void visit(MethodCallExpr n, Void arg) {
                if (sourceLocation.get() != null) {
                    return;
                }
                Range range = n.getName().getRange().orElse(null);
                if (range == null || !JavaUtil.isInRange(position, range, getContent())) {
                    super.visit(n, arg);
                    return;
                }
                IndexPoint ownerType = resolveMethodCallOwnerType(compilationUnit, n);
                CompilationUnit sourceUnit = ownerType != null ? ownerType.resolveCompilationUnit() : null;
                if (sourceUnit == null && ownerType != null) {
                    sourceUnit = ownerType.resolveCompilationUnit();
                }
                MethodDeclaration declaration = sourceUnit != null ?
                    typeResolver.resolveMethodDeclaration(sourceUnit, n, compilationUnit) : null;
                if (declaration != null) {
                    ControllableFile file = resolveSourceFile(ownerType);
                    sourceLocation.set(createLocation(file, ownerType, declaration, file.getContent()));
                }
            }

            @Override
            public void visit(FieldAccessExpr n, Void arg) {
                if (sourceLocation.get() != null) {
                    return;
                }
                Range range = n.getName().getRange().orElse(null);
                if (range == null || !JavaUtil.isInRange(position, range, getContent())) {
                    super.visit(n, arg);
                    return;
                }
                JavaTypeResolver.JavaFieldLookup fieldLookup =
                    typeResolver.resolveFieldLookup(typeResolver.resolveExpressionType(n.getScope(), compilationUnit),
                        n.getNameAsString());
                if (fieldLookup != null) {
                    ControllableFile file = resolveSourceFile(fieldLookup.owner());
                    ;
                    VariableDeclarator fieldVariable = typeResolver.resolveFieldVariable(fieldLookup.owner(),
                        n.getNameAsString());
                    if (fieldVariable != null) {
                        sourceLocation.set(createLocation(file, fieldLookup.owner(), fieldVariable,
                            file.getContent()));
                    }
                }
            }

            @Override
            public void visit(NameExpr n, Void arg) {
                if (sourceLocation.get() != null) {
                    return;
                }
                Range range = n.getName().getRange().orElse(null);
                if (range == null || !JavaUtil.isInRange(position, range, getContent())) {
                    super.visit(n, arg);
                    return;
                }
                Parameter parameter = typeResolver.resolveVisibleParameter(n);
                if (parameter != null) {
                    sourceLocation.set(createLocation(getCurrentFile(), state.getIndexPoint(), parameter,
                        getContent()));
                    return;
                }
                VariableDeclarator localVariable = typeResolver.resolveVisibleLocalVariable(n);
                if (localVariable != null) {
                    sourceLocation.set(createLocation(getCurrentFile(), state.getIndexPoint(), localVariable,
                        getContent()));
                    return;
                }
                JavaTypeResolver.JavaFieldLookup fieldLookup = typeResolver.resolveFieldLookup(state.getIndexPoint(),
                    n.getNameAsString());
                if (fieldLookup != null) {
                    ControllableFile file = resolveSourceFile(fieldLookup.owner());
                    ;
                    VariableDeclarator fieldVariable = typeResolver.resolveFieldVariable(fieldLookup.owner(),
                        n.getNameAsString());
                    if (fieldVariable != null) {
                        sourceLocation.set(createLocation(file, fieldLookup.owner(), fieldVariable,
                            file.getContent()));
                        return;
                    }
                }
                IndexPoint typePoint = typeResolver.resolveType(n.getNameAsString(), compilationUnit);
                if (typePoint != null) {
                    ClassOrInterfaceDeclaration declaration = typeResolver.resolveTypeDeclaration(typePoint);
                    if (declaration != null) {
                        ControllableFile file = resolveSourceFile(typePoint);
                        sourceLocation.set(createLocation(file, typePoint, declaration, file.getContent()));
                    }
                }
            }

            @Override
            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                if (sourceLocation.get() != null) {
                    return;
                }
                Range range = n.getName().getRange().orElse(null);
                if (range == null || !JavaUtil.isInRange(position, range, getContent())) {
                    super.visit(n, arg);
                    return;
                }
                sourceLocation.set(createLocation(getCurrentFile(), state.getIndexPoint(), n, getContent()));
            }

            @Override
            public void visit(ClassOrInterfaceType n, Void arg) {
                if (sourceLocation.get() != null) {
                    return;
                }
                Range range = n.getName().getRange().orElse(null);
                if (range == null || !JavaUtil.isInRange(position, range, getContent())) {
                    super.visit(n, arg);
                    return;
                }
                IndexPoint typePoint = typeResolver.resolveType(n.getNameWithScope(), compilationUnit);
                if (typePoint == null) {
                    typePoint = typeResolver.resolveType(n.getNameAsString(), compilationUnit);
                }
                if (typePoint != null) {
                    ClassOrInterfaceDeclaration declaration = typeResolver.resolveTypeDeclaration(typePoint);
                    if (declaration != null) {
                        ControllableFile file = resolveSourceFile(typePoint);
                        ;
                        sourceLocation.set(createLocation(file, typePoint, declaration, file.getContent()));
                    }
                }
            }
        }, null);
        return sourceLocation.get();
    }

    private String computeMethodHoverTip(int position, CompilationUnit compilationUnit) {
        AtomicReference<MethodDeclaration> methodDeclaration = new AtomicReference<>();
        AtomicReference<MethodSignature> methodSignature = new AtomicReference<>();
        AtomicReference<IndexPoint> source = new AtomicReference<>();
        compilationUnit.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodCallExpr n, Void arg) {
                if (methodSignature.get() != null) {
                    return;
                }
                Range range;
                if ((range = n.getName().getRange().orElse(null)) == null ||
                    !JavaUtil.isInRange(position, range, getContent())) {
                    super.visit(n, arg);
                    return;
                }

                List<IndexPoint> resolvedPointList = resolveMethodCallScopePoints(compilationUnit, n, methodSignature);
                if (resolvedPointList.size() < 2) {
                    return;
                }

                IndexPoint in = resolvedPointList.get(resolvedPointList.size() - 2);
                source.set(in);
                CompilationUnit unit = in != null ? in.resolveCompilationUnit() : null;
                if (unit == null && in != null) {
                    unit = in.resolveCompilationUnit();
                }
                if (unit != null) {
                    methodDeclaration.set(typeResolver.resolveMethodDeclaration(unit, n, compilationUnit));
                }
            }
        }, null);

        if (methodSignature.get() == null || source.get() == null) {
            return "";
        }
        return formatMethodHover(source.get(), methodDeclaration.get(), methodSignature.get());
    }

    private String resolveMethodCallHover(MethodCallExpr n, CompilationUnit compilationUnit) {
        AtomicReference<MethodDeclaration> methodDeclaration = new AtomicReference<>();
        AtomicReference<MethodSignature> methodSignature = new AtomicReference<>();
        List<IndexPoint> lastPointList = resolveMethodCallScopePoints(compilationUnit, n, methodSignature);

        if (methodSignature.get() == null || lastPointList.size() < 2) {
            return "";
        }
        IndexPoint in = lastPointList.get(lastPointList.size() - 2);
        CompilationUnit unit = in != null ? in.resolveCompilationUnit() : null;
        if (unit == null && in != null) {
            unit = in.resolveCompilationUnit();
        }
        if (unit != null) {
            methodDeclaration.set(typeResolver.resolveMethodDeclaration(unit, n, compilationUnit));
        }
        return formatMethodHover(in, methodDeclaration.get(), methodSignature.get());
    }

    private IndexPoint resolveCurrentTypePoint(CompilationUnit compilationUnit, Node node) {
        if (state.getIndexPoint() != null) {
            return state.getIndexPoint();
        }
        ClassOrInterfaceDeclaration typeDeclaration = node.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
        if (typeDeclaration != null) {
            return typeResolver.resolveType(typeDeclaration.getNameAsString(), compilationUnit);
        }
        return null;
    }

    private IndexPoint resolveMethodCallOwnerType(CompilationUnit compilationUnit, MethodCallExpr n) {
        List<IndexPoint> resolvedPointList = resolveMethodCallScopePoints(compilationUnit, n,
            new AtomicReference<MethodSignature>());
        return resolvedPointList.size() >= 2 ? resolvedPointList.get(resolvedPointList.size() - 2) : null;
    }

    private List<IndexPoint> resolveMethodCallScopePoints(CompilationUnit compilationUnit, MethodCallExpr n,
                                                          AtomicReference<MethodSignature> methodSignature) {
        List<IndexPoint> lastPointList = new ArrayList<>();
        IndexPoint currentTypePoint = resolveCurrentTypePoint(compilationUnit, n);
        lastPointList.add(currentTypePoint);
        for (Expression expr : typeResolver.getScopeExpressionList(n)) {
            IndexPoint lastPoint = lastPointList.getLast();
            IndexPoint resolvedPoint = switch (expr) {
                case MethodCallExpr methodCallExpr -> {
                    MethodSignature methodSignature1 = typeResolver.resolveMethodSignature(lastPoint, methodCallExpr,
                        compilationUnit);
                    if (methodSignature1 == null) {
                        yield null;
                    }
                    if (methodSignature != null) {
                        methodSignature.set(methodSignature1);
                    }
                    yield methodSignature1.getReturnType();
                }
                case FieldAccessExpr fieldAccessExpr -> {
                    JavaTypeResolver.JavaFieldLookup fieldLookup =
                        typeResolver.resolveFieldLookup(lastPoint, fieldAccessExpr.getNameAsString());
                    yield fieldLookup != null ? fieldLookup.signature().getType() : null;
                }
                case NameExpr nameExpr -> typeResolver.resolveExpressionType(nameExpr, compilationUnit);
                case ThisExpr ignored -> lastPoint != null ? lastPoint : state.getIndexPoint();
                case SuperExpr ignored -> lastPoint != null ? lastPoint.getParent() :
                    state.getIndexPoint() != null ? state.getIndexPoint().getParent() : null;
                case EnclosedExpr enclosedExpr ->
                    typeResolver.resolveExpressionType(enclosedExpr.getInner(), compilationUnit);
                default -> typeResolver.resolveExpressionType(expr, compilationUnit);
            };
            lastPointList.add(resolvedPoint);
        }
        return lastPointList;
    }

    private String formatMethodDeclarationHover(MethodDeclaration declaration, CompilationUnit compilationUnit) {
        IndexPoint source = resolveCurrentTypePoint(compilationUnit, declaration);
        MethodSignature signature = new MethodSignature(
            declaration.getNameAsString(),
            Access.fromJavaParser(declaration.getAccessSpecifier()),
            declaration.isStatic(),
            typeResolver.resolveType(declaration.getType().asString(), compilationUnit),
            new LinkedHashMap<>(),
            new ArrayList<>()
        );
        declaration.getParameters().forEach(parameter ->
            signature.getParameterMap().put(parameter.getNameAsString(),
                typeResolver.resolveType(parameter.getType().asString(), compilationUnit)));
        return formatMethodHover(source, declaration, signature);
    }

    private String computeSymbolHoverTip(int position, CompilationUnit compilationUnit) {
        AtomicReference<String> hoverTip = new AtomicReference<>("");
        compilationUnit.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(VariableDeclarator n, Void arg) {
                if (!hoverTip.get().isBlank()) {
                    return;
                }
                Range range;
                if ((range = n.getName().getRange().orElse(null)) == null ||
                    !JavaUtil.isInRange(position, range, getContent())) {
                    super.visit(n, arg);
                    return;
                }
                FieldDeclaration fieldDeclaration = n.findAncestor(FieldDeclaration.class).orElse(null);
                if (fieldDeclaration != null) {
                    JavaTypeResolver.JavaFieldLookup fieldLookup = typeResolver.resolveFieldLookup(state.getIndexPoint(),
                        n.getNameAsString());
                    hoverTip.set(fieldLookup != null ?
                        formatFieldHover(fieldLookup.owner(), fieldLookup.signature(), fieldDeclaration, n) :
                        formatLocalHover(n, "field"));
                    return;
                }
                hoverTip.set(formatLocalHover(n, "variable"));
            }

            @Override
            public void visit(Parameter n, Void arg) {
                if (!hoverTip.get().isBlank()) {
                    return;
                }
                Range range;
                if ((range = n.getName().getRange().orElse(null)) == null ||
                    !JavaUtil.isInRange(position, range, getContent())) {
                    super.visit(n, arg);
                    return;
                }
                hoverTip.set(formatParameterHover(n));
            }

            @Override
            public void visit(MethodDeclaration n, Void arg) {
                if (!hoverTip.get().isBlank()) {
                    return;
                }
                Range range;
                if ((range = n.getName().getRange().orElse(null)) == null ||
                    !JavaUtil.isInRange(position, range, getContent())) {
                    super.visit(n, arg);
                    return;
                }
                hoverTip.set(formatMethodDeclarationHover(n, compilationUnit));
            }

            @Override
            public void visit(MethodCallExpr n, Void arg) {
                if (!hoverTip.get().isBlank()) {
                    return;
                }
                Range range;
                if ((range = n.getName().getRange().orElse(null)) == null ||
                    !JavaUtil.isInRange(position, range, getContent())) {
                    super.visit(n, arg);
                    return;
                }
                hoverTip.set(resolveMethodCallHover(n, compilationUnit));
            }

            @Override
            public void visit(FieldAccessExpr n, Void arg) {
                if (!hoverTip.get().isBlank()) {
                    return;
                }
                Range range;
                if ((range = n.getName().getRange().orElse(null)) == null ||
                    !JavaUtil.isInRange(position, range, getContent())) {
                    super.visit(n, arg);
                    return;
                }
                JavaTypeResolver.JavaFieldLookup fieldLookup =
                    typeResolver.resolveFieldLookup(typeResolver.resolveExpressionType(n.getScope(), compilationUnit),
                        n.getNameAsString());
                if (fieldLookup != null) {
                    VariableDeclarator fieldVariable = typeResolver.resolveFieldVariable(fieldLookup.owner(), n.getNameAsString());
                    FieldDeclaration fieldDeclaration = fieldVariable != null ?
                        fieldVariable.findAncestor(FieldDeclaration.class).orElse(null) : null;
                    hoverTip.set(formatFieldHover(fieldLookup.owner(), fieldLookup.signature(), fieldDeclaration,
                        fieldVariable));
                }
            }

            @Override
            public void visit(NameExpr n, Void arg) {
                if (!hoverTip.get().isBlank()) {
                    return;
                }
                Range range;
                if ((range = n.getName().getRange().orElse(null)) == null ||
                    !JavaUtil.isInRange(position, range, getContent())) {
                    super.visit(n, arg);
                    return;
                }
                hoverTip.set(resolveNameHoverTip(n, compilationUnit));
            }

            @Override
            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                if (!hoverTip.get().isBlank()) {
                    return;
                }
                Range range;
                if ((range = n.getName().getRange().orElse(null)) == null ||
                    !JavaUtil.isInRange(position, range, getContent())) {
                    super.visit(n, arg);
                    return;
                }
                IndexPoint typePoint = typeResolver.resolveType(n.getNameAsString(), compilationUnit);
                if (typePoint == null && state.getIndexPoint() != null && state.getIndexPoint().getName().equals(n.getNameAsString())) {
                    typePoint = state.getIndexPoint();
                }
                if (typePoint != null) {
                    hoverTip.set(formatTypeHover(typePoint, n));
                }
            }

            @Override
            public void visit(ClassOrInterfaceType n, Void arg) {
                if (!hoverTip.get().isBlank()) {
                    return;
                }
                Range range;
                if ((range = n.getName().getRange().orElse(null)) == null ||
                    !JavaUtil.isInRange(position, range, getContent())) {
                    super.visit(n, arg);
                    return;
                }
                IndexPoint typePoint = typeResolver.resolveType(n.getNameWithScope(), compilationUnit);
                if (typePoint == null) {
                    typePoint = typeResolver.resolveType(n.getNameAsString(), compilationUnit);
                }
                if (typePoint != null) {
                    ClassOrInterfaceDeclaration declaration = typeResolver.resolveTypeDeclaration(typePoint);
                    hoverTip.set(formatTypeHover(typePoint, declaration));
                }
            }
        }, null);
        return hoverTip.get();
    }

    private String resolveNameHoverTip(NameExpr nameExpr, CompilationUnit compilationUnit) {
        Parameter parameter = typeResolver.resolveVisibleParameter(nameExpr);
        if (parameter != null) {
            return formatParameterHover(parameter);
        }

        VariableDeclarator localVariable = typeResolver.resolveVisibleLocalVariable(nameExpr);
        if (localVariable != null) {
            return formatLocalHover(localVariable, "variable");
        }

        JavaTypeResolver.JavaFieldLookup fieldLookup = typeResolver.resolveFieldLookup(state.getIndexPoint(),
            nameExpr.getNameAsString());
        if (fieldLookup != null) {
            VariableDeclarator fieldVariable = typeResolver.resolveFieldVariable(fieldLookup.owner(), nameExpr.getNameAsString());
            FieldDeclaration fieldDeclaration = fieldVariable != null ?
                fieldVariable.findAncestor(FieldDeclaration.class).orElse(null) : null;
            return formatFieldHover(fieldLookup.owner(), fieldLookup.signature(), fieldDeclaration, fieldVariable);
        }

        IndexPoint typePoint = typeResolver.resolveType(nameExpr.getNameAsString(), compilationUnit);
        if (typePoint != null) {
            return formatTypeHover(typePoint, typeResolver.resolveTypeDeclaration(typePoint));
        }
        return "";
    }

    private String formatMethodHover(IndexPoint source, MethodDeclaration methodDeclaration,
                                     MethodSignature methodSignature) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(typeResolver.formatIndexPointPath(source)).append("\n");
        appendModifiers(sb, methodDeclaration != null ? methodDeclaration.getModifiers() : null,
            methodSignature.getAccess(), methodSignature.isStatic());
        sb.append(methodDeclaration != null ? JavaUtil.normalizeTypeName(methodDeclaration.getType().asString()) :
                typeResolver.formatIndexPointPath(methodSignature.getReturnType()))
            .append(" ").append(methodSignature.getName()).append(" (\n");
        if (methodDeclaration != null) {
            methodDeclaration.getParameters().forEach(parameter -> sb.append("    ")
                .append(JavaUtil.normalizeTypeName(parameter.getType().asString()))
                .append(" ").append(parameter.getNameAsString()).append(",\n"));
        } else {
            methodSignature.getParameterMap().forEach((name, type) -> sb.append("    ")
                .append(typeResolver.formatIndexPointPath(type)).append(" ").append(name).append(",\n"));
        }
        sb.append(")");
        appendJavadocSection(sb, methodDeclaration != null ?
            methodDeclaration.getJavadoc().orElse(null) : null, true);
        return sb.toString();
    }

    private String formatFieldHover(IndexPoint owner, FieldSignature fieldSignature,
                                    FieldDeclaration fieldDeclaration, VariableDeclarator fieldVariable) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(typeResolver.formatIndexPointPath(owner)).append("\n");
        appendModifiers(sb, fieldDeclaration != null ? fieldDeclaration.getModifiers() : null,
            fieldSignature.getAccess(), fieldSignature.isStatic());
        sb.append(fieldVariable != null ? JavaUtil.normalizeTypeName(fieldVariable.getType().asString()) :
                typeResolver.formatIndexPointPath(fieldSignature.getType()))
            .append(" ").append(fieldSignature.getName());
        appendJavadocSection(sb, fieldDeclaration != null ?
            fieldDeclaration.getJavadoc().orElse(null) : null, false);
        return sb.toString();
    }

    private String formatLocalHover(VariableDeclarator variableDeclarator, String kind) {
        return "### " + typeResolver.formatNodeContext(variableDeclarator) + "\n" +
               kind + " " +
               JavaUtil.normalizeTypeName(variableDeclarator.getType().asString()) +
               " " + variableDeclarator.getNameAsString();
    }

    private String formatParameterHover(Parameter parameter) {
        return "### " + typeResolver.formatNodeContext(parameter) + "\n" +
               "parameter " +
               JavaUtil.normalizeTypeName(parameter.getType().asString()) +
               " " + parameter.getNameAsString();
    }

    private String formatTypeHover(IndexPoint typePoint, ClassOrInterfaceDeclaration declaration) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(typeResolver.formatIndexPointPath(typePoint)).append("\n");
        if (declaration != null) {
            appendModifiers(sb, declaration.getModifiers(), null, false);
            sb.append(declaration.isInterface() ? "interface " : "class ")
                .append(declaration.getNameAsString());
            appendJavadocSection(sb, declaration.getJavadoc().orElse(null), false);
        } else {
            sb.append(editor.getIndex().isArrayType(typePoint) ? "array " : "class ")
                .append(typeResolver.formatIndexPointPath(typePoint));
        }
        return sb.toString();
    }

    private void appendModifiers(StringBuilder sb, NodeList<Modifier> modifiers, Access access, boolean isStatic) {
        if (modifiers != null) {
            modifiers.stream().map(modifier -> modifier.getKeyword().asString())
                .forEach(modifier -> sb.append(modifier).append(" "));
            return;
        }
        if (access != null) {
            String accessText = switch (access) {
                case PUBLIC -> "public";
                case PROTECTED -> "protected";
                case PRIVATE -> "private";
                case PACKAGE_PRIVATE -> "";
            };
            if (!accessText.isBlank()) {
                sb.append(accessText).append(" ");
            }
        }
        if (isStatic) {
            sb.append("static ");
        }
    }

    private void appendJavadocSection(StringBuilder sb, Javadoc javadoc, boolean includeParamTags) {
        HoverDoc hoverDoc = parseJavadoc(javadoc);
        if (hoverDoc == null) {
            return;
        }
        boolean hasMainSection = !hoverDoc.description().isBlank() || !hoverDoc.otherTagMap().isEmpty();
        if (hasMainSection) {
            sb.append("\n\n---\n\n");
            if (!hoverDoc.description().isBlank()) {
                sb.append(hoverDoc.description()).append("\n");
            }
            hoverDoc.otherTagMap().forEach((tagName, content) ->
                sb.append("@").append(tagName).append(" ").append(content).append("\n"));
        }
        if (includeParamTags && !hoverDoc.paramTagMap().isEmpty()) {
            sb.append("\n---\n\n");
            hoverDoc.paramTagMap().forEach((name, description) ->
                sb.append(name).append(": ").append(description).append("\n"));
        }
    }

    private HoverDoc parseJavadoc(Javadoc javadoc) {
        if (javadoc == null) {
            return null;
        }
        Map<String, String> paramTagMap = new LinkedHashMap<>();
        Map<String, String> otherTagMap = new LinkedHashMap<>();
        javadoc.getBlockTags().forEach(tag -> {
            if ("param".equals(tag.getTagName())) {
                paramTagMap.put(tag.getName().orElse(""), tag.getContent().toText());
            } else {
                otherTagMap.put(tag.getTagName(), tag.getContent().toText());
            }
        });
        return new HoverDoc(javadoc.getDescription().toText(), paramTagMap, otherTagMap);
    }

    private String getContent() {
        return contentSupplier.get();
    }

    private ControllableFile getCurrentFile() {
        return fileSupplier.get();
    }

    private ControllableFile resolveSourceFile(IndexPoint typePoint) {
        ControllableFile file = typePoint.resolveSourceFile();
        if (file != null) {
            return file;
        }
        return typePoint.resolveSourceFile() != null ? null : getCurrentFile();
    }

    private JavaFile.SourceLocation createLocation(ControllableFile file, IndexPoint point, Node node, String content) {
        if (node == null) {
            return null;
        }
        Range range = node.getRange().orElse(null);
        if (range == null) {
            return null;
        }
        return new JavaFile.SourceLocation(file, point, JavaUtil.getPosition(range.begin, content));
    }

    private record HoverDoc(String description, Map<String, String> paramTagMap, Map<String, String> otherTagMap) {
    }
}
