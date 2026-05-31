package org.a8043.simpleIDE.fileEditor.javaFile;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 针对JavaSymbolChecker保守抑制逻辑的单元测试<br>
 * 这些是误报最易产生的纯AST判定, 不依赖索引/ProjectEditor
 */
public class JavaSymbolCheckerTest {
    @Test
    public void classTypeParameterIsRecognized() {
        CompilationUnit unit = StaticJavaParser.parse("""
            class Box<T> {
                T value;
            }
            """);
        ClassOrInterfaceType type = unit.findAll(ClassOrInterfaceType.class).stream()
            .filter(t -> t.getNameAsString().equals("T")).findFirst().orElseThrow();

        assertTrue(JavaSymbolChecker.isTypeParameter(type, "T"));
    }

    @Test
    public void methodTypeParameterIsRecognized() {
        CompilationUnit unit = StaticJavaParser.parse("""
            class Demo {
                <R> R pick(R input) {
                    return input;
                }
            }
            """);
        ClassOrInterfaceType type = unit.findAll(ClassOrInterfaceType.class).stream()
            .filter(t -> t.getNameAsString().equals("R")).findFirst().orElseThrow();

        assertTrue(JavaSymbolChecker.isTypeParameter(type, "R"));
    }

    @Test
    public void nonTypeParameterIsNotRecognized() {
        CompilationUnit unit = StaticJavaParser.parse("""
            class Demo {
                Unknown value;
            }
            """);
        ClassOrInterfaceType type = unit.findAll(ClassOrInterfaceType.class).stream()
            .filter(t -> t.getNameAsString().equals("Unknown")).findFirst().orElseThrow();

        assertFalse(JavaSymbolChecker.isTypeParameter(type, "Unknown"));
    }

    @Test
    public void qualifierNameOfFieldAccessIsSkipped() {
        CompilationUnit unit = StaticJavaParser.parse("""
            class Demo {
                void test() {
                    int value = System.out.hashCode();
                }
            }
            """);
        NameExpr systemName = unit.findAll(NameExpr.class).stream()
            .filter(n -> n.getNameAsString().equals("System")).findFirst().orElseThrow();

        assertTrue(JavaSymbolChecker.isQualifierName(systemName));
    }

    @Test
    public void standaloneNameIsNotQualifier() {
        CompilationUnit unit = StaticJavaParser.parse("""
            class Demo {
                void test() {
                    int value = unknownVariable;
                }
            }
            """);
        NameExpr name = unit.findAll(NameExpr.class).stream()
            .filter(n -> n.getNameAsString().equals("unknownVariable")).findFirst().orElseThrow();

        assertFalse(JavaSymbolChecker.isQualifierName(name));
    }

    @Test
    public void mistypedTypeQualifierRootIsNotPackageSegment() {
        // Systema.out.println(...) 中的 Systema 是大写开头, 不应被当作包前缀抑制
        assertFalse(JavaSymbolChecker.looksLikePackageSegment("Systema"));
        assertFalse(JavaSymbolChecker.looksLikePackageSegment("System"));
    }

    @Test
    public void lowercaseQualifierRootLooksLikePackage() {
        assertTrue(JavaSymbolChecker.looksLikePackageSegment("java"));
        assertTrue(JavaSymbolChecker.looksLikePackageSegment("com"));
    }

    @Test
    public void explicitImportSuppressesType() {
        CompilationUnit unit = StaticJavaParser.parse("""
            import java.util.List;
            class Demo {
                List items;
            }
            """);

        assertTrue(JavaSymbolChecker.isImported(unit, "List"));
        assertFalse(JavaSymbolChecker.isImported(unit, "Set"));
    }

    @Test
    public void asteriskImportSuppressesAnyType() {
        CompilationUnit unit = StaticJavaParser.parse("""
            import java.util.*;
            class Demo {
                Anything items;
            }
            """);

        assertTrue(JavaSymbolChecker.isImported(unit, "Anything"));
    }

    @Test
    public void staticImportIsNotTreatedAsTypeImport() {
        CompilationUnit unit = StaticJavaParser.parse("""
            import static java.lang.Math.max;
            class Demo {
            }
            """);

        assertFalse(JavaSymbolChecker.isImported(unit, "max"));
        assertTrue(JavaSymbolChecker.isStaticImported(unit, "max"));
    }

    @Test
    public void staticAsteriskImportSuppressesMember() {
        CompilationUnit unit = StaticJavaParser.parse("""
            import static java.lang.Math.*;
            class Demo {
            }
            """);

        assertTrue(JavaSymbolChecker.isStaticImported(unit, "anyMember"));
    }
}
