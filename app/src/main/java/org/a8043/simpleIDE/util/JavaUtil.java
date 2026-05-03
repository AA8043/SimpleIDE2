package org.a8043.simpleIDE.util;

import cn.hutool.core.util.ArrayUtil;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import org.a8043.simpleIDE.project.index.Index;
import org.a8043.simpleIDE.project.index.IndexPoint;
import org.a8043.simpleIDE.project.index.Module;
import org.a8043.simpleIDE.project.index.Package;

import java.util.List;
import java.util.Objects;
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

    public static String[] getClassAbsolutePath(Index index, IndexPoint source, String name, CompilationUnit unit) {
        if (List.of("byte", "short", "int", "long", "float", "double", "char", "boolean", "void").contains(name)) {
            return new String[]{name};
        }

        if (unit != null && source != null) {
            if (Objects.equals(source.getName(), name)) {
                return source.getPath();
            }
        }

        if (unit != null && source != null) {
            String[] path = unit.getTypes().stream().filter(type -> type.getNameAsString().equals(name)).findFirst()
                .map(type -> ArrayUtil.addAll(source.getPath(), new String[]{type.getNameAsString()})).orElse(null);
            if (path != null) {
                return path;
            }
        }

        if (index != null && source != null) {
            Package pkg = source.getPkg();
            if (pkg != null) {
                IndexPoint inPkgPoint = pkg.getPoint(name);
                if (inPkgPoint != null) {
                    return inPkgPoint.getPath();
                }
            }
        }

        if (unit != null) {
            AtomicReference<String[]> importResult = new AtomicReference<>();
            NodeList<ImportDeclaration> importList = unit.getImports();
            importList.forEach(imp -> {
                String[] split = imp.getNameAsString().split("\\.");
                if (!imp.isStatic() && split.length > 0 && split[split.length - 1].equals(name)) {
                    importResult.set(split);
                }
            });
            if (importResult.get() != null) {
                return importResult.get();
            }
        }

        if (index != null) {
            Package pkg = index.getModule("java.base").getPackage(new String[]{"java", "lang"});
            if (pkg != null) {
                IndexPoint langPoint = pkg.getPoint(name);
                if (langPoint != null) {
                    return langPoint.getPath();
                }
            }
        }

        if (unit != null && index != null && source != null) {
            AtomicReference<IndexPoint> indexPoint = new AtomicReference<>();
            unit.getImports().forEach(declaration -> {
                if (declaration.isAsterisk()) {
                    String[] path = ArrayUtil.addAll(declaration.getNameAsString().split("\\."), new String[]{name});
                    IndexPoint point = resolveModuleByPath(index, source, path).getPoint(path);
                    if (point != null) {
                        indexPoint.set(point);
                    }
                }
            });
            if (indexPoint.get() != null) {
                return indexPoint.get().getPath();
            }
        }

        return null;
    }
}
