package org.markdownwriterfx.editor;

import com.vladsch.flexmark.ast.Node;
import com.vladsch.flexmark.parser.Parser;
import io.reactivex.rxjavafx.observables.JavaFxObservable;
import io.reactivex.rxjavafx.schedulers.JavaFxScheduler;
import io.reactivex.schedulers.Schedulers;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.WeakInvalidationListener;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.IndexRange;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import static javafx.scene.input.KeyCode.*;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CharacterHit;
import org.fxmisc.undo.UndoManager;
import org.markdownwriterfx.controls.BottomSlidePane;
import org.markdownwriterfx.editor.MarkdownSyntaxHighlighter.ExtraStyledRanges;
import org.markdownwriterfx.options.MarkdownExtensions;
import org.markdownwriterfx.options.Options;

public class MarkdownEditorPane {

    private final BottomSlidePane borderPane;
    private final MarkdownTextArea textArea;
    private final ParagraphOverlayGraphicFactory overlayGraphicFactory;
    private LineNumberGutterFactory lineNumberGutterFactory;
    private WhitespaceOverlayFactory whitespaceOverlayFactory;
    private ContextMenu contextMenu;
    private final SmartEdit smartEdit;

//    private final FindReplacePane findReplacePane;
//    private final HitsChangeListener findHitsChangeListener;
    private Parser parser;
    private final InvalidationListener optionsListener;
    private String lineSeparator = getLineSeparatorOrDefault();

    public MarkdownEditorPane() {
        textArea = new MarkdownTextArea();
        textArea.setWrapText(true);
        textArea.setUseInitialStyleForInsertion(true);
        textArea.getStyleClass().add("markdown-editor");
        textArea.getStylesheets().add("org/markdownwriterfx/editor/MarkdownEditor.css");
        textArea.getStylesheets().add("org/markdownwriterfx/prism.css");

        //textArea.textProperty().addListener((observable, oldText, newText) -> textChanged(newText));
        // rxJavaFX
        //JavaFxObservable.valuesOf(textArea.textProperty()).subscribe(this::textChanged);
//        JavaFxObservable.valuesOf(textArea.textProperty())
//                .debounce(1, TimeUnit.SECONDS)
//                .map(str -> {
//                    markdownText.set(str);
//                    return str;
//                })
//                //.delay(300, TimeUnit.MILLISECONDS, Schedulers.io())
//                .debounce(1, TimeUnit.SECONDS, Schedulers.computation())
//                .map(this::parseMarkdown)
//                .observeOn(JavaFxScheduler.platform())
//                .subscribe(astRoot -> {
//                    applyHighlighting(astRoot);
//                    markdownAST.set(astRoot);
//                });
        JavaFxObservable.valuesOf(textArea.textProperty())
                .debounce(1, TimeUnit.SECONDS, Schedulers.computation())
                .map(this::parseMarkdown)
                .observeOn(JavaFxScheduler.platform())
                .subscribe(astRoot -> {
                    Platform.runLater(() -> {
                        markdownText.set(textArea.getText());
                        applyHighlighting(astRoot);
                        markdownAST.set(astRoot);
                    });
                });

        textArea.addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, this::showContextMenu);
        textArea.addEventHandler(MouseEvent.MOUSE_PRESSED, this::hideContextMenu);

//        Nodes.addInputMap(textArea, sequence(
//                consume(keyPressed(PLUS, SHORTCUT_DOWN), this::increaseFontSize),
//                consume(keyPressed(MINUS, SHORTCUT_DOWN), this::decreaseFontSize),
//                consume(keyPressed(DIGIT0, SHORTCUT_DOWN), this::resetFontSize),
//                consume(keyPressed(W, ALT_DOWN), this::showWhitespace)
//        ));
        textArea.addEventFilter(KeyEvent.KEY_PRESSED, keyEvent -> {
            KeyCode keyCode = keyEvent.getCode();
            if ((keyCode.equals(PLUS) && keyEvent.isShortcutDown()) || (keyCode.equals(ADD) && keyEvent.isShortcutDown())) {
                increaseFontSize(keyEvent);
                keyEvent.consume();
            }
            if ((keyCode.equals(MINUS) && keyEvent.isShortcutDown()) || (keyCode.equals(SUBTRACT) && keyEvent.isShortcutDown())) {
                decreaseFontSize(keyEvent);
                keyEvent.consume();
            }
            if ((keyCode.equals(DIGIT0) && keyEvent.isShortcutDown()) || (keyCode.equals(NUMPAD0) && keyEvent.isShortcutDown())) {
                resetFontSize(keyEvent);
                keyEvent.consume();
            }
            if (keyCode.equals(SPACE) && keyEvent.isShortcutDown()) {
                showWhitespace(keyEvent);
                keyEvent.consume();
            }
            if (keyCode.equals(N) && keyEvent.isShortcutDown()) {
                showLineNo(keyEvent);
                keyEvent.consume();
            }
        });

        smartEdit = new SmartEdit(this, textArea);

        // add listener to update 'scrollY' property
        ChangeListener<Double> scrollYListener = (observable, oldValue, newValue) -> {
            double value = textArea.estimatedScrollYProperty().getValue().doubleValue();
            double maxValue = textArea.totalHeightEstimateProperty().getOrElse(0.).doubleValue() - textArea.getHeight();
            scrollY.set((maxValue > 0) ? Math.min(Math.max(value / maxValue, 0), 1) : 0);
        };
        textArea.estimatedScrollYProperty().addListener(scrollYListener);
        textArea.totalHeightEstimateProperty().addListener(scrollYListener);

        // create scroll pane
        VirtualizedScrollPane<MarkdownTextArea> scrollPane = new VirtualizedScrollPane<>(textArea);

        // create border pane
        borderPane = new BottomSlidePane(scrollPane);

        overlayGraphicFactory = new ParagraphOverlayGraphicFactory(textArea);
        textArea.setParagraphGraphicFactory(overlayGraphicFactory);
        updateFont();
        updateShowLineNo();
        updateShowWhitespace();

        // initialize properties
        markdownText.set("");
        markdownAST.set(parseMarkdown(""));

        // find/replace
//        findReplacePane = new FindReplacePane(textArea);
//        findHitsChangeListener = this::findHitsChanged;
//        findReplacePane.addListener(findHitsChangeListener);
//        findReplacePane.visibleProperty().addListener((ov, oldVisible, newVisible) -> {
//            if (!newVisible) {
//                borderPane.setBottom(null);
//            }
//        });
        // listen to option changes
        optionsListener = e -> {
            if (textArea.getScene() == null) {
                return; // editor closed but not yet GCed
            }
            if (e == Options.fontFamilyProperty() || e == Options.fontSizeProperty()) {
                updateFont();
            } else if (e == Options.showLineNoProperty()) {
                updateShowLineNo();
            } else if (e == Options.showWhitespaceProperty()) {
                updateShowWhitespace();
            } else if (e == Options.markdownExtensionsProperty()) {
                // re-process markdown if markdown extensions option changes
                parser = null;
                textChanged(textArea.getText());
            }
        };
        WeakInvalidationListener weakOptionsListener = new WeakInvalidationListener(optionsListener);
        Options.fontFamilyProperty().addListener(weakOptionsListener);
        Options.fontSizeProperty().addListener(weakOptionsListener);
        Options.markdownExtensionsProperty().addListener(weakOptionsListener);
        Options.showLineNoProperty().addListener(weakOptionsListener);
        Options.showWhitespaceProperty().addListener(weakOptionsListener);
    }

    private void updateFont() {
        textArea.setStyle("-fx-font-family: '" + Options.getFontFamily()
                + "'; -fx-font-size: " + Options.getFontSize());
    }

    public javafx.scene.Node getNode() {
        return borderPane;
    }

    public UndoManager<?> getUndoManager() {
        return textArea.getUndoManager();
    }

    public SmartEdit getSmartEdit() {
        return smartEdit;
    }

    public void requestFocus() {
        Platform.runLater(() -> textArea.requestFocus());
    }

    private String getLineSeparatorOrDefault() {
        return System.getProperty("line.separator", "\n");
    }

    private String determineLineSeparator(String str) {
        int strLength = str.length();
        for (int i = 0; i < strLength; i++) {
            char ch = str.charAt(i);
            if (ch == '\n') {
                return (i > 0 && str.charAt(i - 1) == '\r') ? "\r\n" : "\n";
            }
        }
        return getLineSeparatorOrDefault();
    }

    // 'markdown' property
    public String getMarkdown() {
        String markdown = textArea.getText();
        if (!lineSeparator.equals("\n")) {
            markdown = markdown.replace("\n", lineSeparator);
        }
        return markdown;
    }

    public void setMarkdown(String markdown) {
        // remember old selection range and scrollY
        IndexRange oldSelection = textArea.getSelection();
        double oldScrollY = textArea.getEstimatedScrollY();

        // replace text
        lineSeparator = determineLineSeparator(markdown);
        textArea.replaceText(markdown);

        // restore old selection range and scrollY
        int newLength = textArea.getLength();
        textArea.selectRange(Math.min(oldSelection.getStart(), newLength), Math.min(oldSelection.getEnd(), newLength));
        Platform.runLater(() -> textArea.estimatedScrollYProperty().setValue(oldScrollY));
    }

    public ObservableValue<String> markdownProperty() {
        return textArea.textProperty();
    }

    // 'markdownText' property
    private final ReadOnlyStringWrapper markdownText = new ReadOnlyStringWrapper();

    public String getMarkdownText() {
        return markdownText.get();
    }

    public ReadOnlyStringProperty markdownTextProperty() {
        return markdownText.getReadOnlyProperty();
    }

    // 'markdownAST' property
    private final ReadOnlyObjectWrapper<Node> markdownAST = new ReadOnlyObjectWrapper<>();

    public Node getMarkdownAST() {
        return markdownAST.get();
    }

    public ReadOnlyObjectProperty<Node> markdownASTProperty() {
        return markdownAST.getReadOnlyProperty();
    }

    // 'selection' property
    public ObservableValue<IndexRange> selectionProperty() {
        return textArea.selectionProperty();
    }

    // 'scrollY' property
    private final ReadOnlyDoubleWrapper scrollY = new ReadOnlyDoubleWrapper();

    public double getScrollY() {
        return scrollY.get();
    }

    public ReadOnlyDoubleProperty scrollYProperty() {
        return scrollY.getReadOnlyProperty();
    }

    private void textChanged(String newText) {
//        if (borderPane.getBottom() != null) {
//            findReplacePane.removeListener(findHitsChangeListener);
//            findReplacePane.textChanged();
//            findReplacePane.addListener(findHitsChangeListener);
//        }
        markdownText.set(newText);

        Node astRoot = parseMarkdown(newText);
        applyHighlighting(astRoot);
        markdownAST.set(astRoot);
    }

    private void findHitsChanged() {
        applyHighlighting(markdownAST.get());
    }

    private Node parseMarkdown(String text) {
        if (parser == null) {
            parser = Parser.builder()
                    .extensions(MarkdownExtensions.getExtensions())
                    .build();
        }
        return parser.parse(text);
    }

    private void applyHighlighting(Node astRoot) {
//        List<ExtraStyledRanges> extraStyledRanges = findReplacePane.hasHits()
//                ? Arrays.asList(
//                        new ExtraStyledRanges("hit", findReplacePane.getHits()),
//                        new ExtraStyledRanges("hit-active", Arrays.asList(findReplacePane.getActiveHit())))
//                : null;
        List<ExtraStyledRanges> extraStyledRanges = null;

        MarkdownSyntaxHighlighter.highlight(textArea, astRoot, extraStyledRanges);
    }

    private void increaseFontSize(KeyEvent e) {
        Options.setFontSize(Options.getFontSize() + 1);
    }

    private void decreaseFontSize(KeyEvent e) {
        Options.setFontSize(Options.getFontSize() - 1);
    }

    private void resetFontSize(KeyEvent e) {
        Options.setFontSize(Options.DEF_FONT_SIZE);
    }

    private void showWhitespace(KeyEvent e) {
        Options.setShowWhitespace(!Options.isShowWhitespace());
    }

    private void showLineNo(KeyEvent e) {
        Options.setShowLineNo(!Options.isShowLineNo());
    }

    private void updateShowLineNo() {
        boolean showLineNo = Options.isShowLineNo();
        if (showLineNo && lineNumberGutterFactory == null) {
            lineNumberGutterFactory = new LineNumberGutterFactory(textArea);
            overlayGraphicFactory.addGutterFactory(lineNumberGutterFactory);
        } else if (!showLineNo && lineNumberGutterFactory != null) {
            overlayGraphicFactory.removeGutterFactory(lineNumberGutterFactory);
            lineNumberGutterFactory = null;
        }
    }

    private void updateShowWhitespace() {
        boolean showWhitespace = Options.isShowWhitespace();
        if (showWhitespace && whitespaceOverlayFactory == null) {
            whitespaceOverlayFactory = new WhitespaceOverlayFactory();
            overlayGraphicFactory.addOverlayFactory(whitespaceOverlayFactory);
        } else if (!showWhitespace && whitespaceOverlayFactory != null) {
            overlayGraphicFactory.removeOverlayFactory(whitespaceOverlayFactory);
            whitespaceOverlayFactory = null;
        }
    }

    public void undo() {
        textArea.getUndoManager().undo();
    }

    public void redo() {
        textArea.getUndoManager().redo();
    }

    public void cut() {
        textArea.cut();
    }

    public void copy() {
        textArea.copy();
    }

    public void paste() {
        textArea.paste();
    }

    public void selectAll() {
        textArea.selectAll();
    }

    //---- context menu -------------------------------------------------------
    private void showContextMenu(ContextMenuEvent e) {
        if (e.isConsumed()) {
            return;
        }

        // create context menu
        if (contextMenu == null) {
            contextMenu = new ContextMenu();
            initContextMenu();
        }

        // update context menu
        CharacterHit hit = textArea.hit(e.getX(), e.getY());
        updateContextMenu(hit.getCharacterIndex().orElse(-1), hit.getInsertionIndex());

        if (contextMenu.getItems().isEmpty()) {
            return;
        }

        // show context menu
        contextMenu.show(textArea, e.getScreenX(), e.getScreenY());
        e.consume();
    }

    private void hideContextMenu(MouseEvent e) {
        if (contextMenu != null) {
            contextMenu.hide();
        }
    }

    private void initContextMenu() {
        SmartEditActions.initContextMenu(this, contextMenu);
    }

    private void updateContextMenu(int characterIndex, int insertionIndex) {
        SmartEditActions.updateContextMenu(this, contextMenu, characterIndex);
    }

    //---- find/replace -------------------------------------------------------
//    public void find(boolean replace) {
//        if (borderPane.getBottom() == null) {
//            borderPane.setBottom(findReplacePane.getNode());
//        }
//
//        findReplacePane.show(replace, true);
//    }
//    public void findNextPrevious(boolean next) {
//        if (borderPane.getBottom() == null) {
//            // show pane
//            find(false);
//            return;
//        }
//
//        if (next) {
//            findReplacePane.findNext();
//        } else {
//            findReplacePane.findPrevious();
//        }
//    }
    //---- class MyStyleClassedTextArea ---------------------------------------
}
