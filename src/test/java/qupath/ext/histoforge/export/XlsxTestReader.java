package qupath.ext.histoforge.export;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Minimal test-only reader for ordinary values in the XLSX parts emitted by FastExcel. */
final class XlsxTestReader {

    private static final String RELATIONSHIP_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private XlsxTestReader() {}

    static WorkbookValues read(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            List<String> sharedStrings = readSharedStrings(zip);
            Document workbook = parse(zip, "xl/workbook.xml");
            Document relationships = parse(zip, "xl/_rels/workbook.xml.rels");
            Map<String, String> targets = relationshipTargets(relationships);
            Map<String, SheetValues> sheets = new LinkedHashMap<>();
            NodeList nodes = workbook.getElementsByTagNameNS("*", "sheet");
            for (int index = 0; index < nodes.getLength(); index++) {
                Element sheet = (Element) nodes.item(index);
                String name = sheet.getAttribute("name");
                String relationshipId = sheet.getAttributeNS(RELATIONSHIP_NS, "id");
                String target = targets.get(relationshipId);
                if (target == null) {
                    throw new IOException("workbook sheet has no relationship target");
                }
                String entry = target.startsWith("/") ? target.substring(1) : "xl/" + target;
                sheets.put(name, readSheet(parse(zip, entry), sharedStrings));
            }
            return new WorkbookValues(sheets);
        }
    }

    record WorkbookValues(Map<String, SheetValues> sheets) {
        WorkbookValues {
            sheets = Collections.unmodifiableMap(new LinkedHashMap<>(sheets));
        }

        SheetValues sheet(String name) {
            SheetValues value = sheets.get(name);
            if (value == null) {
                throw new IllegalArgumentException("missing worksheet " + name);
            }
            return value;
        }
    }

    record SheetValues(Map<Cell, String> values, int rowCount, String dimension) {
        SheetValues {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        String cell(int row, int column) {
            return values.get(new Cell(row, column));
        }

        List<String> row(int row, int columns) {
            List<String> result = new ArrayList<>(columns);
            for (int column = 0; column < columns; column++) {
                result.add(cell(row, column));
            }
            return Collections.unmodifiableList(result);
        }
    }

    private record Cell(int row, int column) {}

    private static SheetValues readSheet(Document document, List<String> sharedStrings)
            throws IOException {
        Map<Cell, String> values = new LinkedHashMap<>();
        int rows = 0;
        NodeList rowNodes = document.getElementsByTagNameNS("*", "row");
        for (int rowIndex = 0; rowIndex < rowNodes.getLength(); rowIndex++) {
            Element row = (Element) rowNodes.item(rowIndex);
            int zeroBasedRow = Integer.parseInt(row.getAttribute("r")) - 1;
            rows = Math.max(rows, zeroBasedRow + 1);
            NodeList children = row.getChildNodes();
            for (int child = 0; child < children.getLength(); child++) {
                Node node = children.item(child);
                if (!(node instanceof Element cell) || !"c".equals(cell.getLocalName())) {
                    continue;
                }
                int column = column(cell.getAttribute("r"));
                String value = cellValue(cell, sharedStrings);
                if (value != null) {
                    values.put(new Cell(zeroBasedRow, column), value);
                }
            }
        }
        NodeList dimensions = document.getElementsByTagNameNS("*", "dimension");
        if (dimensions.getLength() != 1) {
            throw new IOException("worksheet must declare exactly one used range");
        }
        String dimension = ((Element) dimensions.item(0)).getAttribute("ref");
        return new SheetValues(values, rows, dimension);
    }

    private static String cellValue(Element cell, List<String> sharedStrings) throws IOException {
        String type = cell.getAttribute("t");
        if ("inlineStr".equals(type)) {
            return descendantText(cell, "t");
        }
        String raw = firstText(cell, "v");
        if (raw == null) {
            return null;
        }
        if ("s".equals(type)) {
            int index = Integer.parseInt(raw);
            if (index < 0 || index >= sharedStrings.size()) {
                throw new IOException("shared-string index is outside the table");
            }
            return sharedStrings.get(index);
        }
        if ("b".equals(type)) {
            return "1".equals(raw) ? "true" : "false";
        }
        return raw;
    }

    private static List<String> readSharedStrings(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
        if (entry == null) {
            return List.of();
        }
        Document document = parse(zip, entry.getName());
        NodeList strings = document.getElementsByTagNameNS("*", "si");
        List<String> result = new ArrayList<>(strings.getLength());
        for (int index = 0; index < strings.getLength(); index++) {
            result.add(descendantText((Element) strings.item(index), "t"));
        }
        return List.copyOf(result);
    }

    private static Map<String, String> relationshipTargets(Document document) {
        Map<String, String> result = new LinkedHashMap<>();
        NodeList relationships = document.getElementsByTagNameNS("*", "Relationship");
        for (int index = 0; index < relationships.getLength(); index++) {
            Element relationship = (Element) relationships.item(index);
            result.put(relationship.getAttribute("Id"), relationship.getAttribute("Target"));
        }
        return result;
    }

    private static int column(String reference) throws IOException {
        int value = 0;
        int index = 0;
        while (index < reference.length() && Character.isLetter(reference.charAt(index))) {
            char character = Character.toUpperCase(reference.charAt(index));
            value = Math.addExact(Math.multiplyExact(value, 26), character - 'A' + 1);
            index++;
        }
        if (index == 0) {
            throw new IOException("cell reference has no column: " + reference);
        }
        return value - 1;
    }

    private static String firstText(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    private static String descendantText(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < nodes.getLength(); index++) {
            result.append(nodes.item(index).getTextContent());
        }
        return result.toString();
    }

    private static Document parse(ZipFile zip, String entryName) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            throw new IOException("missing XLSX part " + entryName);
        }
        try (InputStream input = zip.getInputStream(entry)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            return factory.newDocumentBuilder().parse(input);
        } catch (ParserConfigurationException | SAXException failure) {
            throw new IOException("invalid XLSX XML part " + entryName, failure);
        }
    }
}
