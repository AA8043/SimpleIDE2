package org.a8043.simpleIDE.views;

import animatefx.animation.*;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.io.watch.SimpleWatcher;
import cn.hutool.core.io.watch.WatchMonitor;
import cn.hutool.core.thread.ThreadUtil;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.fileEditor.*;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.resource.ResourceManager;
import org.a8043.simpleIDE.util.Util;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.markdown4j.Markdown4jProcessor;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class FileTab {
    private static final URL FXML_URL = ResourceUtil.getResource("FileTab.fxml", FileTab.class);
    private static final Map<String, FileEditorFactory> FILE_TYPE_MAP = new HashMap<>();
    private static final List<ToolFactory> TOOL_LIST = new ArrayList<>();

    public interface FileEditorFactory {
        FileEditor create(ControllableFile file, ProjectEditor editor) throws Exception;
    }

    public interface ToolFactory {
        Node create(FileTab fileTab);
    }

    static {
        registerFileType("*", DefaultFile::new);
        registerFileType("java", JavaFile::new);
        registerTool(tab -> new Button("hi"));
    }

    public static void registerFileType(String fileType, FileEditorFactory factory) {
        FILE_TYPE_MAP.put(fileType, factory);
    }

    public static void registerTool(ToolFactory factory) {
        TOOL_LIST.add(factory);
    }

    @Getter
    private final ProjectEditor editor;
    @Getter
    private final FileEditor fileEditor;
    private final boolean isFailedToOpen;
    @FXML
    private HBox tipBox;
    @FXML
    private HBox toolBar;
    @FXML
    private AnchorPane pane;
    @Getter
    private final CodeArea codeArea = new CodeArea();
    private final HBox toolBarContent = new HBox();
    private final HBox searchBox = new HBox();
    private final AtomicReference<Main.ModalController<CompleteBox>> nowCompleteBox = new AtomicReference<>();

    private FileTab(ProjectEditor editor, File file, String content, String fileType) {
        this.editor = editor;
        FileEditor fileEditor;
        boolean isFailedToOpen;
        ControllableFile controllableFile = editor.openFile(file, content);
        try {
            fileEditor = FILE_TYPE_MAP.getOrDefault(fileType, FILE_TYPE_MAP.get("*"))
                .create(controllableFile, editor);
            isFailedToOpen = false;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            isFailedToOpen = true;
            try {
                fileEditor = FILE_TYPE_MAP.get("*").create(controllableFile, editor);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
        this.fileEditor = fileEditor;
        this.isFailedToOpen = isFailedToOpen;
    }

    @SneakyThrows
    public static Tab createTab(ProjectEditor editor, File file) {
        FXMLLoader fxmlLoader = new FXMLLoader(FXML_URL);
        fxmlLoader.setControllerFactory(param -> new FileTab(editor, file, null, FileUtil.getSuffix(file)));
        return new FileTabTab(file, fxmlLoader.load(), fxmlLoader.getController());
    }

    @FXML
    private void initialize() {
        TextField searchField = new TextField();
        searchBox.getChildren().addAll(new Label("search"), searchField, new Button("search.next") {{
            setOnAction(e -> {
                String searchText = searchField.getText();
                if (searchText.isEmpty()) {
                    return;
                }
                String text = codeArea.getText();
                int index = text.indexOf(searchText, codeArea.getCaretPosition());
                if (index >= 0) {
                    codeArea.selectRange(index, index + searchText.length());
                    codeArea.requestFollowCaret();
                } else {
                    int startIndex = text.indexOf(searchText);
                    if (startIndex >= 0) {
                        codeArea.selectRange(startIndex, startIndex + searchText.length());
                        codeArea.requestFollowCaret();
                    } else {
                        showTemporaryTip(new Label("search.notFound"));
                    }
                }
            });
        }}, new Button("search.prev") {{
            setOnAction(e -> {
                String searchText = searchField.getText();
                if (searchText.isEmpty()) {
                    return;
                }
                String text = codeArea.getText();
                int index = text.lastIndexOf(searchText, codeArea.getCaretPosition() - searchText.length());
                if (index >= 0) {
                    codeArea.selectRange(index, index + searchText.length());
                    codeArea.requestFollowCaret();
                } else {
                    int lastIndex = text.lastIndexOf(searchText);
                    if (lastIndex >= 0) {
                        codeArea.selectRange(lastIndex, lastIndex + searchText.length());
                        codeArea.requestFollowCaret();
                    } else {
                        showTemporaryTip(new Label("search.notFound"));
                    }
                }
            });
        }});
        Main.instance.registerKeyBinding("exit", this::returnToolBar,
            new KeyCodeCombination(KeyCode.ESCAPE), searchBox);
        Main.instance.registerKeyBinding("search", () -> {
                borrowToolBar(searchBox);
                searchField.requestFocus();
            },
            new KeyCodeCombination(KeyCode.F, KeyCodeCombination.CONTROL_DOWN), codeArea);

        VirtualizedScrollPane<CodeArea> codeAreaScrollPane = new VirtualizedScrollPane<>(codeArea);
        codeArea.getStylesheets().add("data:text/css," + Main.MAIN_STYLE);
        codeArea.getStylesheets().add("data:text/css," + fileEditor.getHighlightingStyle());
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        pane.getChildren().add(codeAreaScrollPane);
        AnchorPane.setTopAnchor(codeAreaScrollPane, 0.0);
        AnchorPane.setBottomAnchor(codeAreaScrollPane, 0.0);
        AnchorPane.setLeftAnchor(codeAreaScrollPane, 0.0);
        AnchorPane.setRightAnchor(codeAreaScrollPane, 0.0);
        toolBar.setViewOrder(-1);
        tipBox.setViewOrder(-1);

        subscribeRichChanges();
        setOnMouseHover();
        codeArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            KeyCode keyCode = event.getCode();
            if (nowCompleteBox.get() != null) {
                if (keyCode == KeyCode.ESCAPE) {
                    nowCompleteBox.get().close();
                } else {
                    MultipleSelectionModel<CompleteItem> selectionModel =
                        nowCompleteBox.get().getNode().listView.getSelectionModel();
                    if (keyCode == KeyCode.UP) {
                        event.consume();
                        selectionModel.selectPrevious();
                        if (selectionModel.getSelectedIndex() < 0) {
                            selectionModel.selectLast();
                        }
                    } else if (keyCode == KeyCode.DOWN) {
                        event.consume();
                        selectionModel.selectNext();
                        if (selectionModel.getSelectedIndex() >=
                            nowCompleteBox.get().getNode().listView.getItems().size()) {
                            selectionModel.selectFirst();
                        }
                    } else if (keyCode == KeyCode.TAB || keyCode == KeyCode.ENTER) {
                        event.consume();
                        CompleteItem selectedItem = selectionModel.getSelectedItem();
                        if (selectedItem != null) {
                            complete(selectedItem.getText());
                        }
                    }
                }
            } else {
                if (keyCode == KeyCode.TAB) {
                    event.consume();
                    int caretPosition = codeArea.getCaretPosition();
                    String spaces = " ".repeat(4);
                    codeArea.insertText(caretPosition, spaces);
                    codeArea.moveTo(caretPosition + 4);
                }
            }
        });
        codeArea.replaceText(fileEditor.getFile().read());
        codeArea.caretPositionProperty().addListener((obs, oldPos, newPos) -> {
            AtomicBoolean showed = new AtomicBoolean(false);
            fileEditor.getProblemList().forEach(error -> {
                if (error.getStart() <= newPos && newPos <= error.getEnd()) {
                    Label label = new Label(error.getMessage());
                    label.setWrapText(true);
                    showTip(label);
                    showed.set(true);
                }
            });
            if (!showed.get()) {
                closeTip();
            }
        });

        AtomicBoolean isAsking = new AtomicBoolean(false);
        org.a8043.simpleIDE.util.FileUtil.watch(fileEditor.getFile().getFile(), new SimpleWatcher() {
            @Override
            public void onModify(WatchEvent<?> event, Path currentPath) {
                String newContent = fileEditor.getFile().read();
                if (!Objects.equals(newContent, codeArea.getText()) && !newContent.isEmpty() && !isAsking.get()) {
                    isAsking.set(true);
                    Platform.runLater(() -> {
                        Button rereadButton = new Button("fileExternalChange.reread");
                        Button ignoreButton = new Button("fileExternalChange.ignore");
                        Main.ModalController<VBox> modal = Main.instance.showModal("fileExternalChange",
                            new VBox(new Label(ResourceManager.getText("fileExternalChange.description",
                                fileEditor.getFile().getFile().getName())),
                                new BorderPane(null, null, rereadButton,
                                    null, ignoreButton)), 400, 200);
                        modal.setOnClose(() -> isAsking.set(false));

                        rereadButton.setOnAction(e -> {
                            codeArea.replaceText(newContent);
                            modal.close();
                        });
                        ignoreButton.setOnAction(e -> {
                            fileEditor.getFile().setContent(codeArea.getText());
                            fileEditor.getFile().write();
                            modal.close();
                        });
                    });
                }
            }
        }, WatchMonitor.ENTRY_MODIFY);

        toolBarContent.getChildren().addAll(TOOL_LIST.stream().map(factory -> factory.create(this)).toList());
        toolBar.getChildren().add(toolBarContent);

        if (isFailedToOpen) {
            showTemporaryTip(new Label("fileTab.failedToOpenFileTip"));
        }
    }

    private void subscribeRichChanges() {
        AtomicInteger count = new AtomicInteger(0);

        codeArea.multiPlainChanges().subscribe(change -> {
            String newText = codeArea.getText();
            fileEditor.setContent(newText);
            codeArea.setStyleSpans(0, fileEditor.computeHighlighting());
            count.incrementAndGet();
        });

        codeArea.richChanges().filter(ch -> {
            String text = ch.getInserted().getText();
            return ch.getRemoved().getText().isEmpty() && !text.isEmpty() && !hasSpecialCharacters(text);
        }).subscribe(ch -> {
            int caretPosition = codeArea.getCaretPosition();
            if (count.get() > 1 && nowCompleteBox.get() == null) {
                List<CompleteItem> completeItemList =
                    fileEditor.computeCompletion(caretPosition);
                Bounds bounds = codeArea.localToScreen(codeArea.getCaretBounds().orElseThrow());
                CompleteBox completeBox = new CompleteBox(completeItemList, caretPosition);
                nowCompleteBox.set(Main.instance.showModal(null, completeBox, 650, 300,
                    bounds.getMinX() / 2, bounds.getMaxY() / 2, true));
                nowCompleteBox.get().setOnClose(() -> nowCompleteBox.set(null));
                nowCompleteBox.get().getNode().listView.getSelectionModel().selectFirst();
            } else if (nowCompleteBox.get() != null) {
                CompleteBox completeBox = nowCompleteBox.get().getNode();
                ObservableList<CompleteItem> itemList = completeBox.itemList;
                itemList.clear();
                itemList.addAll(fileEditor.computeCompletion(caretPosition));
                completeBox.listView.getSelectionModel().selectFirst();
            }
        });

        codeArea.richChanges().filter(ch -> {
            String text = ch.getInserted().getText();
            return ch.getRemoved().getText().isEmpty() && !text.isEmpty() && hasSpecialCharacters(text);
        }).subscribe(ch -> {
            if (nowCompleteBox.get() != null) {
                nowCompleteBox.get().close();
            }
        });

        codeArea.richChanges().filter(ch -> !ch.getInserted().equals(ch.getRemoved()) &&
                                            ch.getInserted().getText().isEmpty()).subscribe(ch -> {
            if (nowCompleteBox.get() != null &&
                nowCompleteBox.get().getNode().getStartCaretPosition() - 1 == codeArea.getCaretPosition()) {
                nowCompleteBox.get().close();
            } else if (nowCompleteBox.get() != null) {
                ObservableList<CompleteItem> itemList = nowCompleteBox.get().getNode().itemList;
                itemList.clear();
                itemList.addAll(fileEditor.computeCompletion(codeArea.getCaretPosition()));
            }
        });
    }

    private void setOnMouseHover() {
        PauseTransition timer = new PauseTransition(Duration.seconds(1));
        AtomicInteger lastHoverPosition = new AtomicInteger(-1);
        AtomicReference<Main.ModalController<WebView>> view = new AtomicReference<>();
        AtomicReference<Double> mouseX = new AtomicReference<>();
        AtomicReference<Double> mouseY = new AtomicReference<>();

        timer.setOnFinished(event -> {
            if (lastHoverPosition.get() >= 0) {
                String markdown;
                try {
                    markdown = fileEditor.computeHoverTip(lastHoverPosition.get());
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                    markdown = ResourceManager.getText("fileTab.failedToComputeHoverTip");
                }
                if (!markdown.isEmpty()) {
                    String html;
                    try {
                        html = new Markdown4jProcessor().addStyleClass("").process(markdown);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    WebView webView = new WebView();
                    view.set(Main.instance.showModal(null, webView, 400, 300,
                        mouseX.get(), mouseY.get(), true));
                    webView.getEngine().loadContent(html);
                    view.get().setOnClose(() -> {
                        lastHoverPosition.set(-1);
                        view.set(null);
                    });
                }
            }
        });

        codeArea.setOnMouseMoved(event -> {
            int position = codeArea.hit(event.getX(), event.getY()).getInsertionIndex();
            if (position != lastHoverPosition.get()) {
                lastHoverPosition.set(position);
                mouseX.set(event.getX());
                mouseY.set(event.getY());
                timer.stop();
                timer.playFromStart();
                if (view.get() != null) {
                    view.get().close();
                }
            }
        });
    }

    private void complete(String text) {
        int start = nowCompleteBox.get().getNode().getStartCaretPosition() - 1;
        int end = codeArea.getCaretPosition();
        codeArea.replaceText(start, end, text);
        codeArea.moveTo(start + text.length());
        nowCompleteBox.get().close();
    }

    private static boolean hasSpecialCharacters(String text) {
        return text.matches(".*[^a-zA-Z0-9\\u4e00-\\u9fa5.].*");
    }

    public void showTemporaryTip(Node node) {
        showTip(node);
        new Thread(() -> {
            ThreadUtil.sleep(3000);
            closeTip();
        }).start();
    }

    public void showTip(Node node) {
        tipBox.getChildren().setAll(node);
        tipBox.setVisible(true);
        new SlideInRight(tipBox).play();
    }

    public void closeTip() {
        SlideOutRight slide = new SlideOutRight(tipBox);
        slide.setOnFinished(e -> tipBox.setVisible(false));
        slide.play();
    }

    private Node currentToolNode;
    private Animation activeOutAnim;

    public void borrowToolBar(Node newNode) {
        if (newNode == null || currentToolNode == newNode || toolBar.getChildren().contains(newNode)) {
            return;
        }
        if (activeOutAnim != null) {
            activeOutAnim.stop();
        }

        newNode.setOpacity(0);
        newNode.setManaged(true);
        toolBarContent.setManaged(false);
        if (!toolBar.getChildren().contains(newNode)) {
            toolBar.getChildren().add(newNode);
        }
        FadeOut fadeOut = new FadeOut(toolBarContent);
        activeOutAnim = fadeOut.getTimeline();
        fadeOut.getTimeline().setOnFinished(e -> {
            toolBar.getChildren().remove(toolBarContent);
            toolBarContent.setManaged(true);
            toolBarContent.setOpacity(1);
            activeOutAnim = null;
        });
        ParallelTransition inAnim = new ParallelTransition(new FadeIn(newNode).getTimeline(),
            new SlideInRight(newNode).getTimeline());
        inAnim.setInterpolator(Interpolator.EASE_OUT);
        fadeOut.play();
        inAnim.play();
        currentToolNode = newNode;
    }

    public void returnToolBar() {
        if (currentToolNode == null) {
            return;
        }
        if (activeOutAnim != null) {
            activeOutAnim.stop();
        }

        Node oldNode = currentToolNode;
        currentToolNode = null;
        if (!toolBar.getChildren().contains(toolBarContent)) {
            toolBar.getChildren().add(toolBarContent);
        }
        toolBarContent.setOpacity(0);
        toolBarContent.setManaged(true);
        oldNode.setManaged(false);
        FadeOut fadeOut = new FadeOut(oldNode);
        activeOutAnim = fadeOut.getTimeline();
        fadeOut.getTimeline().setOnFinished(e -> {
            toolBar.getChildren().remove(oldNode);
            oldNode.setManaged(true);
            oldNode.setTranslateX(0);
            oldNode.setOpacity(1);
            activeOutAnim = null;
        });
        ParallelTransition inAnim = new ParallelTransition(new FadeIn(toolBarContent).getTimeline(),
            new SlideInLeft(toolBarContent).getTimeline());
        inAnim.setInterpolator(Interpolator.EASE_OUT);
        fadeOut.play();
        inAnim.play();
        codeArea.requestFocus();
    }

    @Getter
    public static class FileTabTab extends Tab {
        private final File file;
        private final String name;

        public FileTabTab(File file, Node node, FileTab tab) {
            super(null, node);
            this.file = file;
            name = file.getName();
            setGraphic(org.a8043.simpleIDE.util.FileUtil.getDisplayItem(file));
            setOnClosed(e -> tab.editor.closeFile(tab.fileEditor.getFile()));
        }
    }

    @Getter
    public class CompleteBox extends VBox {
        private final ObservableList<CompleteItem> itemList;
        private final ListView<CompleteItem> listView;
        private final int startCaretPosition;

        public CompleteBox(List<CompleteItem> completeItemList, int startCaretPosition) {
            itemList = FXCollections.observableList(new ArrayList<>(completeItemList));
            this.startCaretPosition = startCaretPosition;
            listView = new ListView<>(itemList);
            listView.setCellFactory(Util.createListCell(CompleteItem::getNode));

            listView.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2) {
                    CompleteItem completeItem = nowCompleteBox.get().getNode()
                        .listView.getSelectionModel().getSelectedItem();
                    if (completeItem != null) {
                        complete(completeItem.getText());
                    }
                }
            });

            getChildren().add(listView);
        }
    }
}
