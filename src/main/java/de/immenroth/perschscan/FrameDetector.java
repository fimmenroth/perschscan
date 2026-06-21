package de.immenroth.perschscan;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Findet auf einem gescannten Kalenderblatt den Bereich, der den Cartoon
 * umschließt.
 *
 * <p>Die Kalenderblätter sind überwiegend weiß; der Cartoon sitzt links oben,
 * der Kalender steht als Text rechts daneben. Ein Cartoon kann aus
 * <em>mehreren</em> übereinander stehenden, schwarz umrandeten Bild-Panels
 * bestehen, und unter den Panels kann eine zum Cartoon gehörende Bildunterschrift
 * stehen. Beides wird mit erfasst.</p>
 *
 * <p>Vorgehen:</p>
 * <ol>
 *   <li>Graustufen + Otsu-Binarisierung (robust gegen Helligkeitsunterschiede).</li>
 *   <li>Zusammenhangsanalyse (8er-Nachbarschaft) liefert alle dunklen
 *       Komponenten samt Bounding-Box.</li>
 *   <li>Große Komponenten in der linken Blatthälfte gelten als Cartoon-Panels;
 *       horizontal überlappende Panels (gestapelt) werden zu einem Bereich
 *       vereinigt.</li>
 *   <li>Kleine Komponenten direkt unterhalb der Panels (in deren Spalte) werden
 *       als Bildunterschrift erkannt und einbezogen.</li>
 *   <li>Um das Ergebnis kommt ein Randabstand.</li>
 * </ol>
 *
 * <p>Die Analyse läuft auf einer herunterskalierten Kopie; das Ergebnis wird auf
 * die Originalauflösung zurückgerechnet.</p>
 */
final class FrameDetector {

    /** Längste Kante, auf die zur Analyse herunterskaliert wird. */
    private static final int ANALYSIS_MAX_EDGE = 1200;

    private FrameDetector() {
    }

    /** Eine dunkle Zusammenhangskomponente in Analyse-Koordinaten. */
    private record Component(int minX, int minY, int maxX, int maxY, int pixels) {
        int width() {
            return maxX - minX + 1;
        }

        int height() {
            return maxY - minY + 1;
        }

        long bboxArea() {
            return (long) width() * height();
        }

        int centerX() {
            return (minX + maxX) / 2;
        }
    }

    /** Binärmaske in Analyse-Auflösung samt Skalierungsfaktor. */
    private record Mask(boolean[] dark, int w, int h, int scale) {
    }

    /**
     * Erzeugt die herunterskalierte Binärmaske (dunkle Vordergrundpixel) per
     * Otsu-Schwellwert. {@code null}, wenn das Bild zu klein ist.
     */
    private static Mask buildMask(BufferedImage source) {
        int srcW = source.getWidth();
        int srcH = source.getHeight();
        int maxEdge = Math.max(srcW, srcH);
        int scale = Math.max(1, (int) Math.round(maxEdge / (double) ANALYSIS_MAX_EDGE));
        int w = srcW / scale;
        int h = srcH / scale;
        if (w < 10 || h < 10) {
            return null;
        }

        int[] gray = toGray(source, scale, w, h);
        // Otsu liefert die Trennschwelle. Inklusiv (<=) und mit kleinem
        // Sicherheitsaufschlag, damit bei sehr sauberen Vorlagen (kaum Rauschen,
        // dünne Linien) der Schwellwert nicht genau auf dem Tinten-Grauwert
        // landet und die Tinte dann fälschlich als Hintergrund gilt.
        int threshold = otsuThreshold(gray) + 1;
        boolean[] dark = new boolean[w * h];
        for (int i = 0; i < gray.length; i++) {
            dark[i] = gray[i] <= threshold;
        }
        return new Mask(dark, w, h, scale);
    }

    /**
     * Schätzt die Schräglage des Cartoons in Grad. Positiver Wert = Vorlage ist
     * im Uhrzeigersinn verdreht. Um sie lotrecht auszurichten, dreht der Aufrufer
     * das Bild um den negativen Rückgabewert. {@code 0}, wenn keine zuverlässige
     * Schätzung möglich ist.
     *
     * <p>Verfahren: Projektionsprofil. Die dunklen Punkte des größten Panels
     * werden für viele Probewinkel auf die y-Achse projiziert; bei achsparalleler
     * Ausrichtung fallen die geraden Panelkanten auf wenige Zeilen zusammen, das
     * Profil wird also „spitz“ (hohe Energie = Summe der Quadrate). Das ist robust
     * auch für gefüllte oder unregelmäßig berandete Panels – anders als ein
     * Minimal-Rechteck, das an Vorsprüngen kippt.</p>
     */
    static double estimateSkewDegrees(BufferedImage source) {
        Mask mask = buildMask(source);
        if (mask == null) {
            return 0;
        }
        int w = mask.w();
        int h = mask.h();
        boolean[] dark = mask.dark();

        Component primary = primaryPanel(selectPanels(findComponents(dark, w, h), w, h));
        if (primary == null) {
            return 0;
        }

        // Dunkle Punkte im Panel-Bereich einsammeln (auf ~4000 unterabgetastet).
        int bw = primary.width();
        int bh = primary.height();
        int step = Math.max(1, (int) Math.sqrt((double) bw * bh / 4000.0));
        List<int[]> pts = new ArrayList<>();
        for (int y = primary.minY; y <= primary.maxY; y += step) {
            int row = y * w;
            for (int x = primary.minX; x <= primary.maxX; x += step) {
                if (dark[row + x]) {
                    pts.add(new int[]{x, y});
                }
            }
        }
        if (pts.size() < 50) {
            return 0;
        }

        double cx = (primary.minX + primary.maxX) / 2.0;
        double cy = (primary.minY + primary.maxY) / 2.0;
        int maxR = (int) Math.ceil(Math.hypot(bw, bh)) + 2;

        // Grobsuche, dann Feinsuche um das Maximum.
        double best = searchSkew(pts, cx, cy, maxR, -15, 15, 1.0, 0);
        best = searchSkew(pts, cx, cy, maxR, best - 1.0, best + 1.0, 0.1, best);
        return Math.abs(best) < 0.1 ? 0 : best;
    }

    /** Winkel (Grad) mit maximaler Projektionsenergie im Bereich [lo, hi]. */
    private static double searchSkew(List<int[]> pts, double cx, double cy, int maxR,
                                     double lo, double hi, double stepDeg, double fallback) {
        double bestAngle = fallback;
        long bestScore = -1;
        for (double a = lo; a <= hi + 1e-9; a += stepDeg) {
            long score = projectionEnergy(pts, cx, cy, maxR, a);
            if (score > bestScore) {
                bestScore = score;
                bestAngle = a;
            }
        }
        return bestAngle;
    }

    /**
     * Energie des Zeilen- und Spalten-Projektionsprofils, wenn die Punkte um den
     * Winkel rotiert werden. Hoher Wert = scharfe Profile = achsparallele Kanten.
     */
    private static long projectionEnergy(List<int[]> pts, double cx, double cy,
                                         int maxR, double angleDeg) {
        double rad = Math.toRadians(angleDeg);
        double s = Math.sin(rad);
        double c = Math.cos(rad);
        int size = 2 * maxR + 1;
        int[] rows = new int[size];
        int[] cols = new int[size];
        for (int[] p : pts) {
            double dx = p[0] - cx;
            double dy = p[1] - cy;
            int ry = (int) Math.round(-dx * s + dy * c) + maxR;
            int rx = (int) Math.round(dx * c + dy * s) + maxR;
            if (ry >= 0 && ry < size) {
                rows[ry]++;
            }
            if (rx >= 0 && rx < size) {
                cols[rx]++;
            }
        }
        long energy = 0;
        for (int i = 0; i < size; i++) {
            energy += (long) rows[i] * rows[i] + (long) cols[i] * cols[i];
        }
        return energy;
    }

    /**
     * Liefert die Cartoon-Panels: die großen rechteckigen Inhaltsblöcke.
     *
     * <p>Ein Panel ist der größte zusammenhängende Inhalt – entweder ein
     * umrandeter (heller) Kasten oder eine gefüllte (dunkle) Fläche. Maßstab ist
     * der größte gefundene Block; als Panels gelten alle Blöcke in dessen
     * Größenordnung. Die Auswahl ist damit unabhängig von Blattgröße und Füllgrad.
     * Kleinere Elemente (Sprech-/Textblasen, Kalendertext, große Kalenderziffern)
     * liegen deutlich darunter und fallen heraus.</p>
     */
    private static List<Component> selectPanels(List<Component> components, int w, int h) {
        long maxArea = 0;
        for (Component c : components) {
            if (c.width() > w * 0.02 && c.height() > h * 0.02) {
                maxArea = Math.max(maxArea, c.bboxArea());
            }
        }
        long minPanelArea = (long) (0.45 * maxArea);
        List<Component> panels = new ArrayList<>();
        for (Component c : components) {
            if (c.width() > w * 0.02 && c.height() > h * 0.02 && c.bboxArea() >= minPanelArea) {
                panels.add(c);
            }
        }
        return panels;
    }

    /** Größtes Panel (nach Bounding-Box-Fläche) oder {@code null}. */
    private static Component primaryPanel(List<Component> panels) {
        Component primary = null;
        for (Component c : panels) {
            if (primary == null || c.bboxArea() > primary.bboxArea()) {
                primary = c;
            }
        }
        return primary;
    }

    /**
     * Ermittelt die Bounding-Box des Cartoons (alle Panels + Bildunterschrift +
     * Randabstand) im übergebenen Bild.
     *
     * @param source Originalbild des Kalenderblatts
     * @return Rechteck in Originalkoordinaten oder {@code null}, wenn kein
     *         plausibler Cartoon gefunden wurde
     */
    static Rectangle detectFrame(BufferedImage source) {
        Mask mask = buildMask(source);
        if (mask == null) {
            return null;
        }
        int w = mask.w();
        int h = mask.h();
        int scale = mask.scale();
        int srcW = source.getWidth();
        int srcH = source.getHeight();

        List<Component> components = findComponents(mask.dark(), w, h);
        Rectangle region = assembleCartoonRegion(components, w, h);
        if (region == null) {
            return null;
        }

        // Randabstand und Zurückrechnen auf Originalauflösung.
        int margin = Math.max(6, (int) Math.round(0.012 * Math.max(w, h)));
        int sx0 = clamp(region.x - margin, 0, w);
        int sy0 = clamp(region.y - margin, 0, h);
        int sx1 = clamp(region.x + region.width + margin, 0, w);
        int sy1 = clamp(region.y + region.height + margin, 0, h);

        int x0 = clamp(sx0 * scale, 0, srcW);
        int y0 = clamp(sy0 * scale, 0, srcH);
        int x1 = clamp(sx1 * scale, 0, srcW);
        int y1 = clamp(sy1 * scale, 0, srcH);

        Rectangle result = new Rectangle(x0, y0, x1 - x0, y1 - y0);
        return isPlausible(result, srcW, srcH) ? result : null;
    }

    /**
     * Baut aus den Komponenten den Cartoon-Bereich: ein oder mehrere gestapelte
     * Panels plus eine eventuelle Bildunterschrift darunter.
     */
    private static Rectangle assembleCartoonRegion(List<Component> components, int w, int h) {
        // Panel-Kandidaten: große Komponenten in der linken Blatthälfte
        // (der Kalender rechts wird so ausgeschlossen).
        List<Component> panels = selectPanels(components, w, h);

        Component primary = primaryPanel(panels);
        if (primary == null) {
            // Fallback: größte randferne Komponente überhaupt.
            Component largest = null;
            for (Component c : components) {
                if (largest == null || c.bboxArea() > largest.bboxArea()) {
                    largest = c;
                }
            }
            if (largest == null) {
                return null;
            }
            return new Rectangle(largest.minX, largest.minY, largest.width(), largest.height());
        }

        // Ein Kalenderblatt trägt genau einen Cartoon; dessen Panels sind die
        // einzigen großen umrandeten Kästen. Daher werden ALLE erkannten Panels
        // zu einem Bereich vereinigt – egal ob einzeln, gestapelt, nebeneinander
        // oder als Raster (z. B. 2x2) angeordnet und unabhängig von der Größe der
        // Zwischenräume. Der Kalender liefert keine Panels und bleibt außen vor.
        int left = primary.minX, right = primary.maxX, top = primary.minY, bottom = primary.maxY;
        for (Component c : panels) {
            left = Math.min(left, c.minX);
            right = Math.max(right, c.maxX);
            top = Math.min(top, c.minY);
            bottom = Math.max(bottom, c.maxY);
        }

        // Bildunterschrift unter den Panels erfassen.
        //
        // Kandidaten sind kleine Komponenten in einem Band direkt unterhalb der
        // Panels. Sie werden ausgehend von der Panel-Spalte horizontal verkettet:
        // Wörter/Zeichen, die nur durch eine kleine Lücke getrennt sind, gehören
        // zur Unterschrift; eine größere Lücke (der Bundsteg zum Kalender hin)
        // beendet die Verkettung – so wird der rechts stehende Kalender nicht
        // mit eingefangen. Das funktioniert auch über mehrere Zeilen.
        int panelBottom = bottom;
        int lookDown = (int) Math.round(0.08 * h);
        int maxLineHeight = (int) Math.round(0.06 * h);
        List<Component> caption = new ArrayList<>();
        for (Component c : components) {
            if (panels.contains(c)) {
                continue;
            }
            boolean inBand = c.minY >= panelBottom && c.minY <= panelBottom + lookDown;
            boolean textSized = c.height() <= maxLineHeight;
            boolean leftish = c.centerX() < w * 0.6;
            if (inBand && textSized && leftish) {
                caption.add(c);
            }
        }

        int maxGap = (int) Math.round(0.035 * w);
        int capLeft = left, capRight = right, capBottom = bottom;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Component c : caption) {
                boolean horizontallyNear = c.maxX >= capLeft - maxGap
                        && c.minX <= capRight + maxGap;
                if (horizontallyNear && c.centerX() < w * 0.6) {
                    int nl = Math.min(capLeft, c.minX);
                    int nr = Math.max(capRight, c.maxX);
                    int nb = Math.max(capBottom, c.maxY);
                    if (nl != capLeft || nr != capRight || nb != capBottom) {
                        capLeft = nl;
                        capRight = nr;
                        capBottom = nb;
                        changed = true;
                    }
                }
            }
        }

        return new Rectangle(capLeft, top, capRight - capLeft + 1, capBottom - top + 1);
    }

    /**
     * Findet alle dunklen 8er-Zusammenhangskomponenten. Komponenten, die den
     * Bildrand berühren (z. B. Scan-Schatten am Seitenrand), werden verworfen.
     */
    private static List<Component> findComponents(boolean[] dark, int w, int h) {
        boolean[] visited = new boolean[w * h];
        int[] stack = new int[w * h];
        List<Component> result = new ArrayList<>();

        for (int start = 0; start < dark.length; start++) {
            if (!dark[start] || visited[start]) {
                continue;
            }

            int sp = 0;
            stack[sp++] = start;
            visited[start] = true;

            int minX = w, minY = h, maxX = 0, maxY = 0, pixels = 0;
            boolean touchesEdge = false;

            while (sp > 0) {
                int idx = stack[--sp];
                int x = idx % w;
                int y = idx / w;
                pixels++;

                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                    touchesEdge = true;
                }

                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx;
                        if (nx < 0 || nx >= w) continue;
                        int nIdx = ny * w + nx;
                        if (dark[nIdx] && !visited[nIdx]) {
                            visited[nIdx] = true;
                            stack[sp++] = nIdx;
                        }
                    }
                }
            }

            if (!touchesEdge) {
                result.add(new Component(minX, minY, maxX, maxY, pixels));
            }
        }
        return result;
    }

    /**
     * Plausibilitätsprüfung: Der Cartoon hat eine gewisse Mindestgröße und füllt
     * nicht das ganze Blatt aus (sonst vermutlich der Seitenrand des Scans).
     */
    private static boolean isPlausible(Rectangle r, int srcW, int srcH) {
        double areaRatio = (double) r.width * r.height / ((double) srcW * srcH);
        boolean bigEnough = r.width > srcW * 0.08 && r.height > srcH * 0.04;
        boolean notWholePage = areaRatio < 0.92;
        return bigEnough && notWholePage;
    }

    /** Erzeugt eine herunterskalierte Graustufen-Darstellung (0..255). */
    private static int[] toGray(BufferedImage src, int scale, int w, int h) {
        int[] gray = new int[w * h];
        for (int y = 0; y < h; y++) {
            int sy = y * scale;
            for (int x = 0; x < w; x++) {
                int sx = x * scale;
                int rgb = src.getRGB(sx, sy);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                gray[y * w + x] = (int) (0.299 * r + 0.587 * g + 0.114 * b);
            }
        }
        return gray;
    }

    /** Otsu-Schwellwert über das Graustufen-Histogramm. */
    private static int otsuThreshold(int[] gray) {
        int[] hist = new int[256];
        for (int v : gray) {
            hist[v]++;
        }
        int total = gray.length;

        double sum = 0;
        for (int t = 0; t < 256; t++) {
            sum += (double) t * hist[t];
        }

        double sumB = 0;
        int wB = 0;
        double maxVar = -1;
        int threshold = 128;

        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            int wF = total - wB;
            if (wF == 0) break;

            sumB += (double) t * hist[t];
            double mB = sumB / wB;
            double mF = (sum - sumB) / wF;
            double between = (double) wB * wF * (mB - mF) * (mB - mF);
            if (between > maxVar) {
                maxVar = between;
                threshold = t;
            }
        }
        return threshold;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
