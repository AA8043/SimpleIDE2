package org.a8043.simpleIDE.fileEditor;

import javafx.beans.property.DoubleProperty;
import javafx.concurrent.Task;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.resource.ResourceManager;
import org.a8043.simpleIDE.views.FileTab;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

public class XmlFile extends DefaultFile {
    private final SpecialType specialType;
    private final FileTab fileTab;

    public XmlFile(ControllableFile file, ProjectEditor editor, FileTab fileTab) {
        super(file, editor);
        this.fileTab = fileTab;

        if (new File(editor.getProject().getProjectDir(), "pom.xml").equals(file.getFile())) {
            specialType = SpecialType.MAVEN_POM;
        } else {
            specialType = SpecialType.NONE;
        }
    }

    private boolean isFirstChange = true;

    @Override
    protected void onContentChanged() {
        if (isFirstChange) {
            isFirstChange = false;
            return;
        }
        if (specialType == SpecialType.MAVEN_POM) {
            fileTab.showTip(new VBox(new Label("buildTool.scriptChangeTip"), new Button("buildTool.sync") {{
                setOnAction(e -> {
                    fileTab.closeTip();
                    getEditor().runTask(new Task<Void>() {
                        private final DoubleProperty progress = (DoubleProperty) progressProperty();

                        @Override
                        protected Void call() {
                            updateTitle(ResourceManager.getText("buildTool.sync"));
                            progress.set(-1);
                            getEditor().sync();
                            AtomicInteger all = new AtomicInteger();
                            AtomicInteger done = new AtomicInteger();
                            getEditor().getIndex().reindexAll(newValue -> {
                                    all.set(newValue);
                                    progress.set(0);
                                }, () -> progress.set((double) done.incrementAndGet() / all.get()),
                                () -> progress.set(1), e -> {
                                    throw new RuntimeException(e);
                                });
                            return null;
                        }
                    });
                });
            }}));
        }
    }

    private enum SpecialType {
        NONE, MAVEN_POM
    }
}
