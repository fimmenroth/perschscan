package de.immenroth.perschscan;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * Findet auf einem gescannten Kalenderblatt den rechteckigen Rahmen, der den
 * Cartoon umschließt.
 *
 * <p>Die Kalenderblätter sind überwiegend weiß; der Cartoon sitzt in einem
 * kräftig schwarz umrandeten Kasten im oberen Bereich, daneben (rechts) steht
 * der Kalender als Text. Der Rahmen ist die größte zusammenhängende dunkle
 * Struktur des Blatts. Wir suchen daher die dunkle Zusammenhangskomponente mit
 * der größten Bounding-Box und liefern deren Begrenzung zurück.</p>
 *
 * <p>Aus Geschwindigkeits- und Speichergründen arbeitet die Erkennung auf einer
 * herunterskalierten Graustufenkopie; die gefundene Box wird anschließend auf
 * die Originalauflösung zurückgerechnet.</p>
 */
final class FrameDetector {

    /** Längste Kante, auf die zur Analyse herunterskaliert wird. */
    private static final int ANALYSIS_MAX_EDGE = 1200;

    private FrameDetector() {
    }

    /**
     * Ermittelt die Bounding-Box des Cartoon-Rahmens im übergebenen Bild.
     *
     * @param source Originalbild des Kalenderblatts
     * @return Rechteck in Originalkoordinaten oder {@code null}, wenn kein
     *         plausibler Rahmen gefunden wurde
     */
    static Rectangle detectFrame(BufferedImage source) {
        int srcW = source.getWidth();
        int srcH = source.getHeight();

        // Skalierungsfaktor für die Analyse bestimmen.
        int maxEdge = Math.max(srcW, srcH);
        int scale = Math.max(1, (int) Math.round(maxEdge / (double) ANALYSIS_MAX_EDGE));
        int w = srcW / scale;
        int h = srcH / scale;
        if (w < 10 || h < 10) {
            return null;
        }

        int[] gray = toGray(source, scale, w, h);
        int threshold = otsuThreshold(gray);

        // dark[i] == true  =>  Pixel gehört zu (dunkler) Vordergrundstruktur.
        boolean[] dark = new boolean[w * h];
        for (int i = 0; i < gray.length; i++) {
            dark[i] = gray[i] < threshold;
        }

        Rectangle best = largestComponentBounds(dark, w, h);
        if (best == null) {
            return null;
        }

        // Zurückrechnen auf Originalauflösung und auf das Bild begrenzen.
        int x0 = clamp(best.x * scale, 0, srcW);
        int y0 = clamp(best.y * scale, 0, srcH);
        int x1 = clamp((best.x + best.width) * scale, 0, srcW);
        int y1 = clamp((best.y + best.height) * scale, 0, srcH);

        Rectangle result = new Rectangle(x0, y0, x1 - x0, y1 - y0);
        return isPlausibleFrame(result, srcW, srcH) ? result : null;
    }

    /**
     * Liefert die Bounding-Box der dunklen 8er-Zusammenhangskomponente mit der
     * größten Flächenausdehnung. Komponenten, die den Bildrand berühren (z. B.
     * Scan-Schatten am Seitenrand), werden verworfen.
     */
    private static Rectangle largestComponentBounds(boolean[] dark, int w, int h) {
        boolean[] visited = new boolean[w * h];
        int[] stack = new int[w * h];

        long bestArea = 0;
        Rectangle bestRect = null;

        for (int start = 0; start < dark.length; start++) {
            if (!dark[start] || visited[start]) {
                continue;
            }

            int sp = 0;
            stack[sp++] = start;
            visited[start] = true;

            int minX = w, minY = h, maxX = 0, maxY = 0;
            boolean touchesEdge = false;

            while (sp > 0) {
                int idx = stack[--sp];
                int x = idx % w;
                int y = idx / w;

                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                    touchesEdge = true;
                }

                // 8er-Nachbarschaft, damit leicht schräg gescannte Rahmen
                // zusammenhängend bleiben.
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

            if (touchesEdge) {
                continue;
            }

            long area = (long) (maxX - minX + 1) * (maxY - minY + 1);
            if (area > bestArea) {
                bestArea = area;
                bestRect = new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
            }
        }

        return bestRect;
    }

    /**
     * Plausibilitätsprüfung: Der Cartoon-Rahmen hat eine gewisse Mindestgröße
     * und füllt nicht das ganze Blatt aus (sonst wäre es vermutlich der
     * Seitenrand des Scans).
     */
    private static boolean isPlausibleFrame(Rectangle r, int srcW, int srcH) {
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
                // Rec. 601 Luma.
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
