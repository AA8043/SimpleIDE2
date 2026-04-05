package org.a8043.simpleIDE.project.index;

import com.github.javaparser.ast.AccessSpecifier;

public enum Access {
    PUBLIC, PROTECTED, PRIVATE, PACKAGE_PRIVATE;

    public static Access fromJavaParser(AccessSpecifier accessSpecifier) {
        return switch (accessSpecifier) {
            case PUBLIC -> PUBLIC;
            case PROTECTED -> PROTECTED;
            case PRIVATE -> PRIVATE;
            case NONE -> PACKAGE_PRIVATE;
        };
    }
}
