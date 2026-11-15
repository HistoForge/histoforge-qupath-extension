package qupath.ext.histoforge.ui.modules;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.commonmark.Extension;
import org.commonmark.ext.front.matter.YamlFrontMatterBlock;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import qupath.ext.histoforge.util.BrowserLauncher;

/** Render saved publisher Markdown with JavaFX controls, without loading native WebKit. */
final class ModuleReadmeView {
    private static final List<Extension> EXTENSIONS =
            List.of(YamlFrontMatterExtension.create(), TablesExtension.create());

    private ModuleReadmeView() {}

    static Optional<ScrollPane> create(String markdown, String moduleRef, String commit) {
        return create(
                markdown, moduleRef, commit, uri -> BrowserLauncher.open(uri.toASCIIString()));
    }

    static Optional<ScrollPane> create(
            String markdown, String moduleRef, String commit, Consumer<URI> openInBrowser) {
        Node document = Parser.builder().extensions(EXTENSIONS).build().parse(markdown);
        Context context = new Context(moduleRef, commit, openInBrowser);
        VBox content = new VBox(8);
        content.setPadding(new Insets(20, 22, 24, 22));
        addChildren(content, document, context);
        if (content.getChildren().isEmpty()) return Optional.empty();

        ScrollPane scroll = new ScrollPane(content);
        scroll.setId("moduleReadme");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMinHeight(80);
        scroll.setMaxHeight(Double.MAX_VALUE);
        scroll.getStyleClass().add("histoforge-module-scroll");
        return Optional.of(scroll);
    }

    private static void addChildren(VBox target, Node parent, Context context) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            addBlock(target, child, context);
        }
    }

    private static void addBlock(VBox target, Node block, Context context) {
        if (block instanceof YamlFrontMatterBlock) return;
        if (block instanceof Paragraph) {
            target.getChildren().add(inlineFlow(block, context, new Style(false, false, 0)));
        } else if (block instanceof Heading heading) {
            target.getChildren()
                    .add(inlineFlow(block, context, new Style(true, false, heading.getLevel())));
        } else if (block instanceof TableBlock table) {
            target.getChildren().add(table(table, context));
        } else if (block instanceof ListBlock list) {
            addList(target, list, context);
        } else if (block instanceof BlockQuote) {
            VBox quoted = new VBox(4);
            quoted.setStyle(
                    "-fx-border-color: transparent transparent transparent -fx-box-border;"
                            + " -fx-border-width: 0 0 0 2; -fx-padding: 0 0 0 8;");
            addChildren(quoted, block, context);
            target.getChildren().add(quoted);
        } else if (block instanceof FencedCodeBlock fenced) {
            target.getChildren().add(codeBlock(fenced.getLiteral()));
        } else if (block instanceof IndentedCodeBlock indented) {
            target.getChildren().add(codeBlock(indented.getLiteral()));
        } else if (block instanceof ThematicBreak) {
            target.getChildren().add(new Separator());
        } else if (block instanceof HtmlBlock html) {
            target.getChildren().add(literal(html.getLiteral()));
        } else {
            addChildren(target, block, context);
        }
    }

    private static void addList(VBox target, ListBlock list, Context context) {
        Integer start = list instanceof OrderedList ordered ? ordered.getMarkerStartNumber() : null;
        int number = start == null ? 1 : start;
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem)) continue;
            VBox content = new VBox(4);
            addChildren(content, item, context);
            String marker = list instanceof BulletList ? "•" : number++ + ".";
            HBox row = new HBox(6, new Text(marker), content);
            row.setAlignment(Pos.TOP_LEFT);
            HBox.setHgrow(content, Priority.ALWAYS);
            target.getChildren().add(row);
        }
    }

    private static javafx.scene.Node table(TableBlock table, Context context) {
        List<TableRow> rows = new ArrayList<>();
        int columns = 0;
        for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            for (Node child = section.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof TableRow row) {
                    rows.add(row);
                    int count = 0;
                    for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                        if (cell instanceof TableCell) count++;
                    }
                    columns = Math.max(columns, count);
                }
            }
        }
        GridPane grid = new GridPane();
        grid.setMinWidth(0);
        if (columns == 0) return grid;
        for (int index = 0; index < columns; index++) {
            ColumnConstraints constraint = new ColumnConstraints();
            constraint.setPercentWidth(100.0 / columns);
            constraint.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(constraint);
        }
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            int columnIndex = 0;
            for (Node cell = rows.get(rowIndex).getFirstChild();
                    cell != null;
                    cell = cell.getNext()) {
                if (!(cell instanceof TableCell tableCell)) continue;
                TextFlow text = inlineFlow(tableCell, context, new Style(rowIndex == 0, false, 0));
                StackPane frame = new StackPane(text);
                frame.setAlignment(Pos.TOP_LEFT);
                frame.setPadding(new Insets(4, 6, 4, 6));
                frame.setMinWidth(0);
                frame.setStyle("-fx-border-color: -fx-box-border; -fx-border-width: 0 0 1 0;");
                GridPane.setHgrow(frame, Priority.ALWAYS);
                grid.add(frame, columnIndex++, rowIndex);
            }
        }
        // Preserve readable columns on narrow windows without widening the rest of the README.
        grid.setMinWidth(columns * 160.0);
        var scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(false);
        scroll.prefViewportHeightProperty()
                .bind(
                        Bindings.createDoubleBinding(
                                () ->
                                        grid.prefHeight(
                                                Math.max(
                                                        grid.getMinWidth(),
                                                        scroll.getViewportBounds().getWidth())),
                                scroll.viewportBoundsProperty()));
        scroll.setMinHeight(Region.USE_PREF_SIZE);
        scroll.setMaxHeight(Region.USE_PREF_SIZE);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("histoforge-module-scroll");
        return scroll;
    }

    private static TextArea codeBlock(String value) {
        TextArea code = new TextArea(value);
        code.setEditable(false);
        code.setWrapText(true);
        code.setPrefRowCount(Math.min(8, Math.max(2, value.split("\n", -1).length)));
        return code;
    }

    private static TextFlow literal(String value) {
        return new TextFlow(styled(value, new Style(false, false, 0)));
    }

    private static TextFlow inlineFlow(Node parent, Context context, Style style) {
        TextFlow flow = new TextFlow();
        flow.setLineSpacing(2);
        flow.setMinWidth(0);
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            addInline(flow, child, context, style, false);
        }
        return flow;
    }

    private static void addInline(
            TextFlow target, Node node, Context context, Style style, boolean insideLink) {
        if (node instanceof org.commonmark.node.Text text) {
            target.getChildren().add(styled(text.getLiteral(), style));
        } else if (node instanceof Code code) {
            Text value = styled(code.getLiteral(), style);
            value.setStyle(value.getStyle() + " -fx-font-family: monospace;");
            target.getChildren().add(value);
        } else if (node instanceof SoftLineBreak) {
            target.getChildren().add(styled(" ", style));
        } else if (node instanceof HardLineBreak) {
            target.getChildren().add(styled("\n", style));
        } else if (node instanceof HtmlInline html) {
            target.getChildren().add(styled(html.getLiteral(), style));
        } else if (node instanceof Link link) {
            URI destination =
                    insideLink
                            ? null
                            : ModuleReadmeLinks.destination(
                                    link.getDestination(), context.moduleRef(), context.commit());
            if (destination != null) {
                target.getChildren()
                        .add(hyperlink(plainText(link), destination, context.openInBrowser()));
            } else {
                addInlineChildren(target, link, context, style, true);
            }
        } else if (node instanceof Image image) {
            String label = plainText(image);
            URI destination =
                    insideLink
                            ? null
                            : ModuleReadmeLinks.destination(
                                    image.getDestination(), context.moduleRef(), context.commit());
            target.getChildren()
                    .add(
                            destination == null
                                    ? styled(label, style)
                                    : hyperlink(label, destination, context.openInBrowser()));
        } else if (node instanceof StrongEmphasis) {
            addInlineChildren(target, node, context, style.withBold(), insideLink);
        } else if (node instanceof Emphasis) {
            addInlineChildren(target, node, context, style.withItalic(), insideLink);
        } else {
            addInlineChildren(target, node, context, style, insideLink);
        }
    }

    private static void addInlineChildren(
            TextFlow target, Node parent, Context context, Style style, boolean insideLink) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            addInline(target, child, context, style, insideLink);
        }
    }

    private static Hyperlink hyperlink(String label, URI destination, Consumer<URI> openInBrowser) {
        Hyperlink link = new Hyperlink(label);
        link.setPadding(Insets.EMPTY);
        link.setTooltip(new Tooltip("Open in browser: " + destination.toASCIIString()));
        link.setOnAction(event -> openInBrowser.accept(destination));
        return link;
    }

    private static Text styled(String value, Style style) {
        Text text = new Text(value);
        String css = "-fx-fill: -fx-text-base-color;";
        if (style.bold()) css += " -fx-font-weight: bold;";
        if (style.italic()) css += " -fx-font-style: italic;";
        if (style.headingLevel() > 0) {
            int size =
                    switch (style.headingLevel()) {
                        case 1 -> 19;
                        case 2 -> 17;
                        default -> 15;
                    };
            css += " -fx-font-size: " + size + "px;";
        }
        text.setStyle(css);
        return text;
    }

    private static String plainText(Node parent) {
        StringBuilder text = new StringBuilder();
        appendPlainText(text, parent);
        return text.isEmpty() ? "Open link" : text.toString();
    }

    private static void appendPlainText(StringBuilder out, Node node) {
        if (node instanceof org.commonmark.node.Text text) {
            out.append(text.getLiteral());
        } else if (node instanceof Code code) {
            out.append(code.getLiteral());
        } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            out.append(' ');
        } else if (node instanceof Image image) {
            out.append("Image: ");
            for (Node child = image.getFirstChild(); child != null; child = child.getNext()) {
                appendPlainText(out, child);
            }
        } else {
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                appendPlainText(out, child);
            }
        }
    }

    private record Context(String moduleRef, String commit, Consumer<URI> openInBrowser) {}

    private record Style(boolean bold, boolean italic, int headingLevel) {
        Style withBold() {
            return new Style(true, italic, headingLevel);
        }

        Style withItalic() {
            return new Style(bold, true, headingLevel);
        }
    }
}
