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

    /**
     * Ermittelt die Bounding-Box des Cartoons (alle Panels + Bildunterschrift +
     * Randabstand) im übergebenen Bild.
     *
     * @param source Originalbild des Kalenderblatts
     * @return Rechteck in Originalkoordinaten oder {@code null}, wenn kein
     *         plausibler Cartoon gefunden wurde
     */
    static Rectangle detectFrame(BufferedImage source) {
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

        List<Component> components = findComponents(dark, w, h);
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
        List<Component> panels = new ArrayList<>();
        for (Component c : components) {
            boolean leftHalf = c.centerX() < w * 0.5;
            boolean bigEnough = c.width() > w * 0.12 && c.height() > h * 0.03;
            if (leftHalf && bigEnough) {
                panels.add(c);
            }
        }

        if (panels.isEmpty()) {
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

        // Primäres Panel = größtes; daran alle horizontal überlappenden
        // (übereinander gestapelten) Panels anschließen.
        Component primary = panels.get(0);
        for (Component c : panels) {
            if (c.bboxArea() > primary.bboxArea()) {
                primary = c;
            }
        }

        int left = primary.minX, right = primary.maxX, top = primary.minY, bottom = primary.maxY;
        for (Component c : panels) {
            if (horizontalOverlapRatio(c.minX, c.maxX, left, right) > 0.4) {
                left = Math.min(left, c.minX);
                right = Math.max(right, c.maxX);
                top = Math.min(top, c.minY);
                bottom = Math.max(bottom, c.maxY);
            }
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

    /** Anteil der Überlappung zweier Intervalle bezogen auf das kleinere. */
    private static double horizontalOverlapRatio(int aMin, int aMax, int bMin, int bMax) {
        int overlap = Math.min(aMax, bMax) - Math.max(aMin, bMin);
        if (overlap <= 0) {
            return 0;
        }
        int smaller = Math.min(aMax - aMin, bMax - bMin);
        return smaller <= 0 ? 0 : (double) overlap / smaller;
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
