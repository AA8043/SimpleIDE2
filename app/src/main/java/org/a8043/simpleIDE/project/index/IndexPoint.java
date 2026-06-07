package org.a8043.simpleIDE.project.index;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ZipUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONSupport;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import lombok.*;
import org.a8043.simpleIDE.fileEditor.ControllableFile;
import org.a8043.simpleIDE.project.ProjectModule;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 索引点(类)
 */
@Getter
@ToString(exclude = "fieldList")
@EqualsAndHashCode(callSuper = false)
public class IndexPoint extends JSONSupport {
    private final String name;
    private final Package pkg;
    @Setter(AccessLevel.PACKAGE)
    private IndexPoint parent;
    @Setter(AccessLevel.PACKAGE)
    private IndexPoint enclosingType;
    private final Index index;
    private final List<MethodSignature> methodList = new ArrayList<>();
    private final List<FieldSignature> fieldList = new ArrayList<>();

    public IndexPoint(String name, Package pkg, IndexPoint parent, Index index) {
        this.name = name;
        this.pkg = pkg;
        this.parent = parent;
        this.index = index;
    }

    public String[] getPath() {
        String[] nameArray = {name};
        if (isBasicType() || pkg == null) {
            return nameArray;
        }
        if (enclosingType != null) {
            return ArrayUtil.addAll(enclosingType.getPath(), nameArray);
        }
        String[] pkgPath = pkg.getPath();
        if (pkgPath == null || ArrayUtil.equals(pkgPath, new Object[]{null})) {
            return nameArray;
        } else {
            return ArrayUtil.addAll(pkgPath, nameArray);
        }
    }

    public String[] getSourcePath() {
        if (enclosingType != null) {
            return enclosingType.getSourcePath();
        }
        return getPath();
    }

    public boolean isNestedType() {
        return enclosingType != null;
    }

    public String getQualifiedName() {
        return ArrayUtil.join(getPath(), ".");
    }

    public String getImportQualifiedName() {
        return getQualifiedName();
    }

    public List<MethodSignature> getMethodList(String name) {
        return methodList.stream().filter(method -> method.getName().equals(name)).toList();
    }

    public FieldSignature getField(String name) {
        return fieldList.stream().filter(field -> field.getName().equals(name)).findFirst().orElse(null);
    }

    public boolean isBasicType() {
        return pkg.getModule() == index.getModuleList().get(1);
    }

    private ControllableFile sourceFileCache;
    private CompilationUnit sourceUnitCache;

    public CompilationUnit resolveCompilationUnit() {
        if (sourceUnitCache != null) {
            return sourceUnitCache;
        }

        ParseResult<CompilationUnit> result = index.getEditor().getJavaParser()
            .parse(resolveSourceFile().getContent());
        CompilationUnit unit = result.getResult().orElse(null);
        if (pkg.getModule().getProjectModule().getLocation() != ProjectModule.Location.PROJECT) {
            sourceUnitCache = unit;
        }
        return unit;
    }

    public ControllableFile resolveSourceFile() {
        if (sourceFileCache != null) {
            return sourceFileCache;
        }

        String[] pathArray = getSourcePath();
        StringBuilder pathBuilder = new StringBuilder();
        for (String aPath : pathArray) {
            pathBuilder.append(aPath).append("/");
        }
        String path = pathBuilder.substring(0, pathBuilder.length() - 1) + ".java";

        ControllableFile file = switch (pkg.getModule().getProjectModule().getLocation()) {
            case JDK -> index.getEditor().openFile(name + ".java",
                IoUtil.readUtf8(ZipUtil.getStream(index.getStandardLibraryZip(),
                    index.getStandardLibraryZip().getEntry(
                        getPkg().getModule().getProjectModule().getName() + "/" + path))));

            case DEPENDENCY -> {
                // TODO: 查找依赖源码
                throw new UnsupportedOperationException();
            }

            case PROJECT -> pkg.getModule().getProjectModule().getSrcDir().stream()
                .map(dir -> new File(dir, path)).filter(File::exists).findFirst()
                .map(index.getEditor()::openFile).orElse(null);
        };
        if (pkg.getModule().getProjectModule().getLocation() != ProjectModule.Location.PROJECT) {
            sourceFileCache = file;
        }
        return file;
    }

    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject().set("path", ArrayUtil.join(getPath(), "."))
            .set("moduleName", pkg.getModule().getCacheName())
            .set("methodList", methodList).set("fieldList", fieldList);
        if (enclosingType != null) {
            json.set("enclosingType", new JSONObject()
                .set("moduleName", enclosingType.getPkg().getModule().getCacheName())
                .set("path", enclosingType.getQualifiedName()));
        }
        return json;
    }
}
