package org.a8043.simpleIDE.fileEditor;

import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import lombok.AllArgsConstructor;
import lombok.Value;
import org.a8043.simpleIDE.project.index.IndexPoint;
import org.a8043.simpleIDE.project.index.Package;
import org.a8043.simpleIDE.resource.ResourceManager;

@Value
@AllArgsConstructor
public class CompleteItem {
    Node node;
    int caretPositionAtBegin;
    int start;
    String text;
    String importQualifiedName;

    public CompleteItem(String imageName, String name, String description,
                        int caretPositionAtBegin, int start, String text) {
        this(new DefaultNode(imageName, name, description), caretPositionAtBegin, start, text, null);
    }

    public CompleteItem(Package completion, int caretPositionAtBegin, int start) {
        this("class", completion.getName(), completion.getFullName(),
            caretPositionAtBegin, start, completion.getFullName());
    }

    public CompleteItem(IndexPoint completion, int caretPositionAtBegin, int start) {
        this(new DefaultNode("class", completion.getName(),
                completion.getPkg() != null ? completion.getQualifiedName() : "."),
            caretPositionAtBegin, start, completion.getName(),
            completion.getPkg() != null ? completion.getImportQualifiedName() : null);
    }

    public static class DefaultNode extends BorderPane {
        public DefaultNode(String imageName, String name, String description) {
            Label nameLabel = new Label(name);
            nameLabel.setContentDisplay(ContentDisplay.LEFT);
            nameLabel.setGraphic(ResourceManager.createImageView(imageName, 16, 16));
            setLeft(nameLabel);

            Label descLabel = new Label(description);
            descLabel.getStyleClass().add("completion-description-label");
            setRight(descLabel);
        }
    }
}
