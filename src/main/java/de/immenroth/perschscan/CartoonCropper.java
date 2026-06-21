package de.immenroth.perschscan;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Kommandozeilen-Anwendung: liest gescannte oder fotografierte Kalenderblätter
 * (Einzelbilder oder mehrseitige PDFs), schneidet den aufgedruckten Cartoon aus
 * und speichert ihn unter einem fortlaufenden Namen.
 *
 * <pre>
 *   Aufruf:  java -jar perschscan.jar &lt;eingabe&gt; [ausgabeordner]
 *
 *   &lt;eingabe&gt;       Datei (Bild oder PDF) oder Ordner mit Eingabedateien
 *   [ausgabeordner] Zielordner (Vorgabe: &lt;eingabe&gt;/cartoons bzw. ./cartoons)
 * </pre>
 */
public final class CartoonCropper {

    private static final Pattern IMAGE_EXT =
            Pattern.compile(".*\\.(png|jpe?g|tif?f|bmp|gif)$", Pattern.CASE_INSENSITIVE);

    /** Auflösung, in der PDF-Seiten gerendert werden. */
    private static final float PDF_RENDER_DPI = 300f;

    /** Präfix und Stellenzahl für die fortlaufenden Ausgabedateien. */
    private static final String OUTPUT_PREFIX = "cartoon_";
    private static final String OUTPUT_FORMAT = "png";

    public static void main(String[] args) {
        if (args.length < 1 || isHelp(args[0])) {
            printUsage();
            return;
        }

        Path input = Path.of(args[0]);
        if (!Files.exists(input)) {
            System.err.println("Eingabe nicht gefunden: " + input);
            System.exit(2);
        }

        Path outputDir = args.length >= 2
                ? Path.of(args[1])
                : defaultOutputDir(input);

        try {
            Files.createDirectories(outputDir);
            List<Path> inputs = collectInputs(input);
            if (inputs.isEmpty()) {
                System.err.println("Keine verarbeitbaren Eingabedateien gefunden in: " + input);
                System.exit(1);
            }

            new CartoonCropper(outputDir).run(inputs);
        } catch (IOException e) {
            System.err.println("Fehler: " + e.getMessage());
            System.exit(1);
        }
    }

    private final Path outputDir;
    private int counter;

    private CartoonCropper(Path outputDir) {
        this.outputDir = outputDir;
        this.counter = nextStartIndex(outputDir);
    }

    private void run(List<Path> inputs) throws IOException {
        int saved = 0;
        for (Path file : inputs) {
            for (BufferedImage page : loadPages(file)) {
                String label = file.getFileName().toString();
                if (process(page, label)) {
                    saved++;
                }
            }
        }
        System.out.printf(Locale.ROOT,
                "Fertig. %d Cartoon(s) gespeichert in: %s%n", saved, outputDir.toAbsolutePath());
    }

    /** Verarbeitet eine einzelne Seite; gibt {@code true} bei Erfolg zurück. */
    private boolean process(BufferedImage page, String sourceLabel) throws IOException {
        Rectangle frame = FrameDetector.detectFrame(page);
        if (frame == null) {
            System.err.printf(Locale.ROOT,
                    "  ! Kein Cartoon-Rahmen erkannt in '%s' – übersprungen.%n", sourceLabel);
            return false;
        }

        BufferedImage cartoon = page.getSubimage(frame.x, frame.y, frame.width, frame.height);
        Path target = outputDir.resolve(String.format(Locale.ROOT, "%s%04d.%s",
                OUTPUT_PREFIX, counter++, OUTPUT_FORMAT));

        // getSubimage teilt den Datenpuffer mit dem Original – vor dem Schreiben
        // in ein eigenständiges Bild kopieren.
        BufferedImage copy = new BufferedImage(cartoon.getWidth(), cartoon.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        copy.getGraphics().drawImage(cartoon, 0, 0, null);

        ImageIO.write(copy, OUTPUT_FORMAT, target.toFile());
        System.out.printf(Locale.ROOT, "  -> %s  (aus '%s', %dx%d px)%n",
                target.getFileName(), sourceLabel, frame.width, frame.height);
        return true;
    }

    /** Lädt alle Seiten einer Eingabedatei (PDF: mehrere, Bild: genau eine). */
    private static List<BufferedImage> loadPages(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        List<BufferedImage> pages = new ArrayList<>();

        if (name.endsWith(".pdf")) {
            try (PDDocument doc = Loader.loadPDF(file.toFile())) {
                PDFRenderer renderer = new PDFRenderer(doc);
                for (int i = 0; i < doc.getNumberOfPages(); i++) {
                    pages.add(renderer.renderImageWithDPI(i, PDF_RENDER_DPI, ImageType.RGB));
                }
            }
        } else {
            BufferedImage img = ImageIO.read(file.toFile());
            if (img != null) {
                pages.add(img);
            } else {
                System.err.println("  ! Konnte Bild nicht lesen: " + file);
            }
        }
        return pages;
    }

    /** Sammelt Eingabedateien aus einer Datei oder einem Ordner (sortiert). */
    private static List<Path> collectInputs(Path input) throws IOException {
        if (Files.isRegularFile(input)) {
            return List.of(input);
        }
        try (Stream<Path> stream = Files.list(input)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(CartoonCropper::isSupported)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    private static boolean isSupported(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".pdf") || IMAGE_EXT.matcher(n).matches();
    }

    private static Path defaultOutputDir(Path input) {
        Path base = Files.isDirectory(input) ? input : input.toAbsolutePath().getParent();
        if (base == null) {
            base = Path.of(".");
        }
        return base.resolve("cartoons");
    }

    /**
     * Bestimmt den nächsten freien fortlaufenden Index, damit wiederholte Läufe
     * die Nummerierung fortsetzen, statt vorhandene Dateien zu überschreiben.
     */
    private static int nextStartIndex(Path outputDir) {
        Pattern p = Pattern.compile(Pattern.quote(OUTPUT_PREFIX) + "(\\d+)\\." + OUTPUT_FORMAT,
                Pattern.CASE_INSENSITIVE);
        int max = 0;
        if (Files.isDirectory(outputDir)) {
            try (Stream<Path> stream = Files.list(outputDir)) {
                for (Path f : (Iterable<Path>) stream::iterator) {
                    Matcher m = p.matcher(f.getFileName().toString());
                    if (m.matches()) {
                        max = Math.max(max, Integer.parseInt(m.group(1)));
                    }
                }
            } catch (IOException ignored) {
                // Ordner ggf. leer/neu – Start bei 1.
            }
        }
        return max + 1;
    }

    private static boolean isHelp(String arg) {
        return "-h".equals(arg) || "--help".equals(arg) || "help".equals(arg);
    }

    private static void printUsage() {
        System.out.println("""
                perschscan – schneidet Cartoons aus gescannten Kalenderblättern aus.

                Aufruf:
                  java -jar perschscan.jar <eingabe> [ausgabeordner]

                  <eingabe>        Bild (PNG/JPG/TIFF/BMP/GIF) oder PDF,
                                   oder ein Ordner mit solchen Dateien
                  [ausgabeordner]  Zielordner (Vorgabe: <eingabe>/cartoons)

                Die Cartoons werden fortlaufend als cartoon_0001.png,
                cartoon_0002.png, ... gespeichert. Vorhandene Nummern werden
                fortgesetzt, nicht überschrieben.
                """);
    }
}
