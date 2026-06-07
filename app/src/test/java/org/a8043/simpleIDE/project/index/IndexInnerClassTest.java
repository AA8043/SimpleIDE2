package org.a8043.simpleIDE.project.index;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import org.a8043.simpleIDE.fileEditor.CompleteItem;
import org.a8043.simpleIDE.project.ProjectModule;
import org.a8043.simpleIDE.util.JavaUtil;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;

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
        assertEquals("demo.Outer", inner.getImportQualifiedName());
        assertEquals("Outer.Inner", inner.getImportReferenceName());
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

    @Test
    public void completionUsesOuterImportAndNestedReferenceForNestedType() {
        Index index = new Index(null);
        Module module = new Module(new ProjectModule("demo-module", ProjectModule.Location.PROJECT,
            List.of(), List.of(new File(".")), List.of(), List.of(), List.of()), index);
        Package pkg = new Package(module, module.getPackageList().getFirst(), "demo", index);
        IndexPoint outer = new IndexPoint("Outer", pkg, null, index);
        IndexPoint inner = new IndexPoint("Inner", pkg, null, index);
        inner.setEnclosingType(outer);

        CompleteItem item = new CompleteItem(null, 10, 5,
            inner.getImportReferenceName(), inner.getImportQualifiedName());

        assertEquals("Outer.Inner", item.getText());
        assertEquals("demo.Outer", item.getImportQualifiedName());
    }

    @Test
    public void resolvesNestedChildrenOfOwner() {
        Index index = new Index(null);
        Module module = new Module(new ProjectModule("demo-module", ProjectModule.Location.PROJECT,
            List.of(), List.of(new File(".")), List.of(), List.of(), List.of()), index);
        Package pkg = new Package(module, module.getPackageList().getFirst(), "demo", index);
        IndexPoint outer = new IndexPoint("Outer", pkg, null, index);
        IndexPoint inner = new IndexPoint("Inner", pkg, null, index);
        IndexPoint topLevelInner = new IndexPoint("Inner", pkg, null, index);
        inner.setEnclosingType(outer);
        index.getIndexList().add(outer);
        index.getIndexList().add(inner);
        index.getIndexList().add(topLevelInner);

        List<IndexPoint> nestedPoints = JavaUtil.resolveNestedPoints(outer);

        assertEquals(1, nestedPoints.size());
        assertSame(inner, nestedPoints.getFirst());
    }

    @Test
    public void resolvesQualifiedNameFromStaticFieldAccessChains() {
        FieldAccessExpr nested = StaticJavaParser.parseExpression("Outer.Inner").asFieldAccessExpr();
        FieldAccessExpr qualified = StaticJavaParser.parseExpression("demo.Outer.Inner").asFieldAccessExpr();
        Expression dynamic = StaticJavaParser.parseExpression("factory().Inner");

        assertEquals("Outer.Inner", JavaUtil.resolveQualifiedName(nested));
        assertEquals("demo.Outer.Inner", JavaUtil.resolveQualifiedName(qualified));
        assertNull(JavaUtil.resolveQualifiedName(dynamic));
    }
}
