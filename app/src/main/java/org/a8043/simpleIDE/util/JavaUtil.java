package org.a8043.simpleIDE.util;

import cn.hutool.core.util.ArrayUtil;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.a8043.simpleIDE.project.ProjectModule;
import org.a8043.simpleIDE.project.index.Index;
import org.a8043.simpleIDE.project.index.IndexPoint;
import org.a8043.simpleIDE.project.index.Module;
import org.a8043.simpleIDE.project.index.Package;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class JavaUtil {
    public static Module resolveModuleByPath(Index index, String[] path) {
        if (index == null || path == null) {
            return null;
        }
        return index.getModuleList().stream().filter(module -> module.hasPoint(path)).findFirst().orElse(null);
    }

    public static Module resolveModuleByPath(Index index, IndexPoint source, String[] path) {
        if (index == null || source == null || path == null) {
            return null;
        }
        Module module = source.getPkg().getModule();
        List<Module> requireList = module.getRequireList();
        if (requireList == null) {
            requireList = index.getModuleList();
        }
        return requireList.stream().filter(module1 -> module1.hasPoint(path)).findFirst()
            .orElseGet(() -> module.hasPoint(path) ? module : null);
    }

    public static IndexPoint resolvePointByName(Index index, IndexPoint source, String name, CompilationUnit unit) {
        if (index != null) {
            IndexPoint basicType = index.getBasicTypeMap().get(name);
            if (basicType != null) {
                return basicType;
            }
        }

        if (unit != null && source != null) {
            if (Objects.equals(source.getName(), name)) {
                return source;
            }
        }

        if (index != null && unit != null && source != null) {
            TypeDeclaration<?> declaration = unit.getTypes().stream().filter(type -> type.getNameAsString().equals(name))
                .findFirst().orElse(null);
            if (declaration != null) {
                Module module = source.getPkg() != null ? source.getPkg().getModule() : null;
                if (module != null) {
                    IndexPoint point = module.getPoint(buildTypePath(unit, declaration));
                    if (point != null) {
                        return point;
                    }
                }
                if (Objects.equals(source.getName(), declaration.getNameAsString())) {
                    return source;
                }
            }
        }

        if (index != null && source != null) {
            Package pkg = source.getPkg();
            if (pkg != null) {
                IndexPoint inPkgPoint = pkg.getPoint(name);
                if (inPkgPoint != null) {
                    return inPkgPoint;
                }
            }
        }

        if (index != null && unit != null) {
            AtomicReference<String[]> importResult = new AtomicReference<>();
            NodeList<ImportDeclaration> importList = unit.getImports();
            importList.forEach(imp -> {
                String[] split = imp.getNameAsString().split("\\.");
                if (!imp.isStatic() && split.length > 0 && split[split.length - 1].equals(name)) {
                    importResult.set(split);
                }
            });
            if (importResult.get() != null) {
                IndexPoint point = resolvePointByPath(index,
                    Objects.requireNonNull(source).getPkg().getModule(), importResult.get());
                if (point != null) {
                    return point;
                }
            }
        }

        if (index != null) {
            Package pkg = index.getModule("java.base").getPackage(new String[]{"java", "lang"});
            if (pkg != null) {
                IndexPoint langPoint = pkg.getPoint(name);
                if (langPoint != null) {
                    return langPoint;
                }
            }
        }

        if (unit != null && index != null && source != null) {
            AtomicReference<IndexPoint> indexPoint = new AtomicReference<>();
            unit.getImports().forEach(declaration -> {
                if (declaration.isAsterisk()) {
                    String[] path = ArrayUtil.addAll(declaration.getNameAsString().split("\\."), new String[]{name});
                    IndexPoint point = resolvePointByPath(index, source.getPkg().getModule(), path);
                    if (point != null) {
                        indexPoint.set(point);
                    }
                }
            });
            if (indexPoint.get() != null) {
                return indexPoint.get();
            }
        }

        return null;
    }

    public static IndexPoint resolveType(Index index, IndexPoint source, String typeName, CompilationUnit unit) {
        if (index == null || typeName == null || typeName.isBlank()) {
            return null;
        }

        String normalizedTypeName = normalizeTypeName(typeName);
        int arrayDepth = countArrayDimensions(normalizedTypeName);
        String componentTypeName = stripArraySuffix(normalizedTypeName);
        componentTypeName = eraseTypeArguments(componentTypeName).trim();
        if (componentTypeName.startsWith("? extends ")) {
            componentTypeName = componentTypeName.substring("? extends ".length()).trim();
        } else if (componentTypeName.startsWith("? super ")) {
            componentTypeName = componentTypeName.substring("? super ".length()).trim();
        } else if ("?".equals(componentTypeName)) {
            componentTypeName = "Object";
        }

        IndexPoint componentType = resolveDeclaredType(index, source, componentTypeName, unit);
        if (componentType == null) {
            return null;
        }
        for (int i = 0; i < arrayDepth; i++) {
            componentType = index.getOrCreateArrayType(componentType);
        }
        return componentType;
    }

    public static IndexPoint resolvePointByPath(Index index, Module source, String[] path) {
        IndexPoint pointInSelf = source.getPoint(path);
        if (pointInSelf != null) {
            return pointInSelf;
        }

        Set<Module> visited = new HashSet<>();
        Queue<Module> queue = new LinkedList<>();

        source.getRequireList().stream().filter(dep -> !visited.contains(dep)).forEach(dep -> {
            visited.add(dep);
            queue.offer(dep);
        });

        while (!queue.isEmpty()) {
            Module current = queue.poll();
            IndexPoint point = current.getPoint(path);
            if (point != null) {
                return point;
            }
            for (Module transitiveDep : current.getRequireList()) {
                if (!visited.contains(transitiveDep)) {
                    visited.add(transitiveDep);
                    queue.offer(transitiveDep);
                }
            }
        }

        Module unnamedModule = index.getModuleList().getFirst();
        if (unnamedModule != null && !visited.contains(unnamedModule)) {
            IndexPoint pointInUnnamed = unnamedModule.getPoint(path);
            if (pointInUnnamed != null) {
                return pointInUnnamed;
            }
        }

        for (Module module : index.getModuleList()) {
            if (module.getProjectModule() != null &&
                module.getProjectModule().getLocation() == ProjectModule.Location.JDK &&
                !visited.contains(module)) {
                IndexPoint point = module.getPoint(path);
                if (point != null) {
                    return point;
                }
            }
        }

        return null;
    }

    public static int getPosition(Position pos, String text) {
        if (text.isEmpty()) {
            return 0;
        }
        String[] lines = text.split("\n", -1);
        int targetLine = Math.max(1, pos.line);
        if (targetLine > lines.length) {
            return text.length();
        }
        int position = 0;
        for (int i = 0; i < targetLine - 1; i++) {
            position += lines[i].length() + 1;
        }
        int targetColumn = Math.max(1, pos.column);
        position += Math.min(targetColumn - 1, lines[targetLine - 1].length());
        return Math.min(position, text.length());
    }

    public static boolean isInRange(int position, Range range, String text) {
        int start = getPosition(range.begin, text);
        int end = getPosition(range.end, text) + 1;
        return position >= start && position <= end;
    }

    public static boolean isPositionInString(CompilationUnit compilationUnit, int position, String content) {
        AtomicBoolean result = new AtomicBoolean(false);
        compilationUnit.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(StringLiteralExpr n, Void arg) {
                Range range = n.getRange().orElse(null);
                if (range != null) {
                    int start = getPosition(range.begin, content) + 1;
                    int end = getPosition(range.end, content) - 1;
                    if (position >= start && position <= end) {
                        result.set(true);
                        return;
                    }
                }
                super.visit(n, arg);
            }
        }, null);
        return result.get();
    }

    public static String normalizeTypeName(String typeName) {
        return typeName.replace("final ", "").replace("...", "[]").trim();
    }

    public static int countArrayDimensions(String typeName) {
        String normalizedTypeName = normalizeTypeName(typeName);
        int count = 0;
        while (normalizedTypeName.endsWith("[]")) {
            count++;
            normalizedTypeName = normalizedTypeName.substring(0, normalizedTypeName.length() - 2).trim();
        }
        return count;
    }

    public static String stripArraySuffix(String typeName) {
        String normalizedTypeName = normalizeTypeName(typeName);
        while (normalizedTypeName.endsWith("[]")) {
            normalizedTypeName = normalizedTypeName.substring(0, normalizedTypeName.length() - 2).trim();
        }
        return normalizedTypeName;
    }

    public static String eraseTypeArguments(String typeName) {
        StringBuilder builder = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < typeName.length(); i++) {
            char ch = typeName.charAt(i);
            if (ch == '<') {
                depth++;
                continue;
            }
            if (ch == '>') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private static String[] buildTypePath(CompilationUnit unit, TypeDeclaration<?> declaration) {
        String[] packagePath = unit.getPackageDeclaration().map(pkg -> pkg.getNameAsString().split("\\."))
            .orElse(new String[0]);
        return ArrayUtil.addAll(packagePath, new String[]{declaration.getNameAsString()});
    }

    private static IndexPoint resolveDeclaredType(Index index, IndexPoint source, String typeName, CompilationUnit unit) {
        if (typeName.contains(".")) {
            String[] path = typeName.split("\\.");
            if (source != null && source.getPkg() != null && source.getPkg().getModule() != null) {
                IndexPoint point = resolvePointByPath(index, source.getPkg().getModule(), path);
                if (point != null) {
                    return point;
                }
            }
            Module module = resolveModuleByPath(index, path);
            if (module != null) {
                IndexPoint point = module.getPoint(path);
                if (point != null) {
                    return point;
                }
            }
        }
        return resolvePointByName(index, source, typeName, unit);
    }
}
