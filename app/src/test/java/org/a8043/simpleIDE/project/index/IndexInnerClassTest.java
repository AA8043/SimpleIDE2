package org.a8043.simpleIDE.project.index;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.a8043.simpleIDE.project.ProjectModule;
import org.a8043.simpleIDE.util.JavaUtil;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;

public class IndexInnerClassTest {
    @Test
    public void resolvesNestedTypePathsAndNames() {
        CompilationUnit unit = StaticJavaParser.parse("""
            package demo;
            
            class Outer {
                class Inner {
                }
                Inner field;
            }
            """);
        Index index = new Index(null);
        Module module = new Module(new ProjectModule("demo-module", ProjectModule.Location.PROJECT,
            List.of(), List.of(new File(".")), List.of(), List.of(), List.of()), index);
        Package pkg = new Package(module, module.getPackageList().getFirst(), "demo", index);
        IndexPoint outer = new IndexPoint("Outer", pkg, null, index);
        IndexPoint inner = new IndexPoint("Inner", pkg, null, index);
        inner.setEnclosingType(outer);
        index.getIndexList().add(outer);
        index.getIndexList().add(inner);

        assertArrayEquals(new String[]{"demo", "Outer", "Inner"}, inner.getPath());
        assertArrayEquals(new String[]{"demo", "Outer"}, inner.getSourcePath());
        assertSame(inner, JavaUtil.resolveType(index, outer, "Inner", unit));
        assertSame(inner, JavaUtil.resolveType(index, outer, "Outer.Inner", unit));
        assertSame(inner, JavaUtil.resolveType(index, outer, "demo.Outer.Inner", unit));
    }

    @Test
    public void resolvesExplicitImportedNestedTypeBySimpleName() {
        CompilationUnit unit = StaticJavaParser.parse("""
            package use;
            
            import demo.Outer.Inner;
            
            class Use {
                Inner field;
            }
            """);
        Index index = new Index(null);
        Module module = new Module(new ProjectModule("demo-module", ProjectModule.Location.PROJECT,
            List.of(), List.of(new File(".")), List.of(), List.of(), List.of()), index);
        Package demoPkg = new Package(module, module.getPackageList().getFirst(), "demo", index);
        Package usePkg = new Package(module, module.getPackageList().getFirst(), "use", index);
        IndexPoint outer = new IndexPoint("Outer", demoPkg, null, index);
        IndexPoint inner = new IndexPoint("Inner", demoPkg, null, index);
        inner.setEnclosingType(outer);
        IndexPoint use = new IndexPoint("Use", usePkg, null, index);
        index.getIndexList().add(outer);
        index.getIndexList().add(inner);
        index.getIndexList().add(use);

        assertSame(inner, JavaUtil.resolveType(index, use, "Inner", unit));
    }
}
