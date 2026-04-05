module SimpleIDE.main {
    requires java.desktop;
    requires java.logging;
    requires java.scripting;
    requires java.sql;

    requires org.slf4j;
    requires com.github.javaparser.core;
    requires cn.hutool;
    requires org.apache.commons.io;
    requires markdown4j;
    requires jdk.jsobject;

    requires maven.model;
    requires maven.invoker;
    requires plexus.utils;

    requires javafx.base;
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.swing;
    requires javafx.web;
    requires org.fxmisc.flowless;
    requires org.fxmisc.richtext;
    requires animatefx;

    requires static lombok;
    requires cfr;
    requires org.jetbrains.annotations;
    requires jdk.jdi;

    exports org.a8043.simpleIDE;
}