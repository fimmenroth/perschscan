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
 *   Aufruf:  java -jar perschscan.jar [--rotate=&lt;90|-90&gt;] &lt;eingabe&gt; [ausgabeordner]
 *
 *   --rotate=&lt;grad&gt; dreht jede Eingabeseite vor der Erkennung um +90 (im
 *                   Uhrzeigersinn) bzw. -90 Grad (gegen den Uhrzeigersinn).
 *                   Erlaubt: 90, -90 (bzw. 270), cw, ccw.
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
        // Optionen (--flag) und Positionsargumente trennen.
        int rotation = 0;
        List<String> positionals = new ArrayList<>();
        for (String arg : args) {
            if (isHelp(arg)) {
                printUsage();
                return;
            }
            if (arg.startsWith("--rotate=") || arg.startsWith("-r=")) {
                Integer r = parseRotation(arg.substring(arg.indexOf('=') + 1));
                if (r == null) {
                    System.err.println("Ungültiger Wert für --rotate (erlaubt: 90, -90, cw, ccw).");
                    System.exit(2);
                }
                rotation = r;
            } else if (arg.startsWith("-")) {
                System.err.println("Unbekannte Option: " + arg);
                System.exit(2);
            } else {
                positionals.add(arg);
            }
        }

        if (positionals.isEmpty()) {
            printUsage();
            return;
        }

        Path input = Path.of(positionals.get(0));
        if (!Files.exists(input)) {
            System.err.println("Eingabe nicht gefunden: " + input);
            System.exit(2);
        }

        Path outputDir = positionals.size() >= 2
                ? Path.of(positionals.get(1))
                : defaultOutputDir(input);

        try {
            Files.createDirectories(outputDir);
            List<Path> inputs = collectInputs(input);
            if (inputs.isEmpty()) {
                System.err.println("Keine verarbeitbaren Eingabedateien gefunden in: " + input);
                System.exit(1);
            }

            new CartoonCropper(outputDir, rotation).run(inputs);
        } catch (IOException e) {
            System.err.println("Fehler: " + e.getMessage());
            System.exit(1);
        }
    }

    private final Path outputDir;
    /** Drehung in Vielfachen von 90° im Uhrzeigersinn (-1, 0 oder +1). */
    private final int quarterTurns;
    private int counter;

    private CartoonCropper(Path outputDir, int rotationDegrees) {
        this.outputDir = outputDir;
        this.quarterTurns = rotationDegrees / 90;
        this.counter = nextStartIndex(outputDir);
    }

    private void run(List<Path> inputs) throws IOException {
        int saved = 0;
        for (Path file : inputs) {
            for (BufferedImage page : loadPages(file)) {
                BufferedImage oriented = rotate(page, quarterTurns);
                BufferedImage straight = deskew(oriented);
                String label = file.getFileName().toString();
                if (process(straight, label)) {
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

    /** Untergrenze (Grad), ab der eine Schräglage korrigiert wird. */
    private static final double MIN_SKEW_DEGREES = 0.15;
    /** Obergrenze (Grad); größere Schätzungen gelten als unzuverlässig. */
    private static final double MAX_SKEW_DEGREES = 20.0;

    /**
     * Richtet den Cartoon lotrecht aus: ermittelt die Schräglage und dreht die
     * Seite gegen, sodass der Rahmen achsparallel steht. Sehr kleine oder
     * unplausibel große Schätzungen werden ignoriert.
     */
    private static BufferedImage deskew(BufferedImage page) {
        double skew = FrameDetector.estimateSkewDegrees(page);
        if (Math.abs(skew) < MIN_SKEW_DEGREES || Math.abs(skew) > MAX_SKEW_DEGREES) {
            return page;
        }
        System.out.printf(Locale.ROOT, "  ~ Schräglage %.2f° korrigiert.%n", skew);
        return rotateArbitrary(page, -skew);
    }

    /**
     * Dreht ein Bild um einen beliebigen Winkel (Grad, im Uhrzeigersinn).
     * Die Leinwand wird vergrößert, damit nichts abgeschnitten wird; neue
     * Flächen werden weiß gefüllt (wie das Kalenderpapier).
     */
    private static BufferedImage rotateArbitrary(BufferedImage src, double degrees) {
        double rad = Math.toRadians(degrees);
        double sin = Math.abs(Math.sin(rad));
        double cos = Math.abs(Math.cos(rad));
        int w = src.getWidth();
        int h = src.getHeight();
        int nw = (int) Math.ceil(w * cos + h * sin);
        int nh = (int) Math.ceil(w * sin + h * cos);

        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = dst.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, nw, nh);
        g.translate((nw - w) / 2.0, (nh - h) / 2.0);
        g.rotate(rad, w / 2.0, h / 2.0);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    /**
     * Dreht ein Bild um Vielfache von 90°. {@code +1} dreht im Uhrzeigersinn,
     * {@code -1} gegen den Uhrzeigersinn; {@code 0} liefert das Bild unverändert.
     */
    private static BufferedImage rotate(BufferedImage src, int quarterTurns) {
        int turns = ((quarterTurns % 4) + 4) % 4;
        if (turns == 0) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = (turns % 2) != 0;
        BufferedImage dst = new BufferedImage(swap ? h : w, swap ? w : h,
                BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                switch (turns) {
                    case 1 -> dst.setRGB(h - 1 - y, x, rgb);          // 90° im Uhrzeigersinn
                    case 2 -> dst.setRGB(w - 1 - x, h - 1 - y, rgb);  // 180°
                    default -> dst.setRGB(y, w - 1 - x, rgb);         // 270° = 90° gegen Uhrzeigersinn
                }
            }
        }
        return dst;
    }

    /** Liest einen Rotationswert (90, -90, 270, cw, ccw) als Grad ein. */
    private static Integer parseRotation(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "90", "cw" -> 90;
            case "-90", "270", "ccw" -> -90;
            case "0" -> 0;
            default -> null;
        };
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
                  java -jar perschscan.jar [--rotate=<90|-90>] <eingabe> [ausgabeordner]

                  --rotate=<grad>  dreht jede Eingabeseite vor der Erkennung:
                                   90 bzw. cw  = 90 Grad im Uhrzeigersinn,
                                   -90 bzw. ccw = 90 Grad gegen den Uhrzeigersinn.
                  <eingabe>        Bild (PNG/JPG/TIFF/BMP/GIF) oder PDF,
                                   oder ein Ordner mit solchen Dateien
                  [ausgabeordner]  Zielordner (Vorgabe: <eingabe>/cartoons)

                Die Cartoons werden fortlaufend als cartoon_0001.png,
                cartoon_0002.png, ... gespeichert. Vorhandene Nummern werden
                fortgesetzt, nicht überschrieben.
                """);
    }
}
