package qupath.ext.histoforge.export;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Corrects FastExcel's fixed A1 worksheet dimensions without buffering worksheet contents. */
final class XlsxDimensionNormalizer {

    private static final byte[] FASTEXCEL_DIMENSION =
            "<dimension ref=\"A1\"/>".getBytes(StandardCharsets.UTF_8);
    private static final Pattern WORKSHEET_ENTRY =
            Pattern.compile("xl/worksheets/sheet([1-9][0-9]*)[.]xml");
    private static final int PREFIX_LIMIT = 64 * 1024;

    private XlsxDimensionNormalizer() {}

    static void rewrite(Path source, Path destination, List<String> dimensions) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        dimensions = List.copyOf(dimensions);
        boolean[] written = new boolean[dimensions.size()];
        try (ZipFile zipInput = new ZipFile(source.toFile());
                OutputStream fileOutput = Files.newOutputStream(destination);
                ZipOutputStream zipOutput = new ZipOutputStream(fileOutput)) {
            zipOutput.setLevel(Deflater.BEST_SPEED);
            Enumeration<? extends ZipEntry> entries = zipInput.entries();
            while (entries.hasMoreElements()) {
                ZipEntry inputEntry = entries.nextElement();
                ZipEntry outputEntry = outputEntry(inputEntry);
                zipOutput.putNextEntry(outputEntry);
                int worksheet = worksheetIndex(inputEntry.getName());
                try (InputStream entryInput = zipInput.getInputStream(inputEntry)) {
                    if (worksheet < 0) {
                        entryInput.transferTo(zipOutput);
                    } else {
                        if (worksheet >= dimensions.size() || written[worksheet]) {
                            throw new IOException(
                                    "workbook worksheet inventory does not match output");
                        }
                        replaceDimension(entryInput, zipOutput, dimensions.get(worksheet));
                        written[worksheet] = true;
                    }
                }
                zipOutput.closeEntry();
            }
        }
        for (boolean worksheetWritten : written) {
            if (!worksheetWritten) {
                throw new IOException("workbook is missing a declared worksheet");
            }
        }
    }

    private static ZipEntry outputEntry(ZipEntry input) {
        ZipEntry output = new ZipEntry(input.getName());
        output.setMethod(ZipEntry.DEFLATED);
        if (input.getTime() >= 0) {
            output.setTime(input.getTime());
        }
        if (input.getComment() != null) {
            output.setComment(input.getComment());
        }
        return output;
    }

    private static int worksheetIndex(String entryName) throws IOException {
        Matcher matcher = WORKSHEET_ENTRY.matcher(entryName);
        if (!matcher.matches()) {
            return -1;
        }
        try {
            return Math.subtractExact(Integer.parseInt(matcher.group(1)), 1);
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IOException("invalid workbook worksheet entry " + entryName, invalid);
        }
    }

    private static void replaceDimension(InputStream input, OutputStream output, String dimension)
            throws IOException {
        byte[] prefix = input.readNBytes(PREFIX_LIMIT);
        int offset = indexOf(prefix, FASTEXCEL_DIMENSION);
        if (offset < 0) {
            throw new IOException("FastExcel worksheet dimension marker is missing");
        }
        output.write(prefix, 0, offset);
        output.write(("<dimension ref=\"" + dimension + "\"/>").getBytes(StandardCharsets.UTF_8));
        output.write(
                prefix,
                offset + FASTEXCEL_DIMENSION.length,
                prefix.length - offset - FASTEXCEL_DIMENSION.length);
        input.transferTo(output);
    }

    private static int indexOf(byte[] source, byte[] target) {
        int last = source.length - target.length;
        for (int offset = 0; offset <= last; offset++) {
            int index = 0;
            while (index < target.length && source[offset + index] == target[index]) {
                index++;
            }
            if (index == target.length) {
                return offset;
            }
        }
        return -1;
    }
}
