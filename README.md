# perschscan

Eine kleine Java-Anwendung, die **gescannte oder fotografierte Kalenderblätter**
(z. B. Perscheid-Abreißkalender) verarbeitet: Sie erkennt den aufgedruckten
**Cartoon**, schneidet ihn zu und speichert ihn unter einem **fortlaufenden
Namen** (`cartoon_0001.png`, `cartoon_0002.png`, …).

## Idee / Funktionsweise

Ein Kalenderblatt ist überwiegend weiß. Der Cartoon sitzt in einem kräftig
schwarz umrandeten Kasten, daneben steht der Kalender als Text. Dieser Rahmen
ist die **größte zusammenhängende dunkle Struktur** des Blatts.

1. Die Seite wird in Graustufen umgewandelt und mit einem **Otsu-Schwellwert**
   binarisiert (robust gegen unterschiedliche Helligkeit von Scans/Fotos).
2. Über eine **Zusammenhangsanalyse (8er-Nachbarschaft)** wird die dunkle
   Komponente mit der größten Bounding-Box gesucht – das ist der Cartoon-Rahmen.
   Komponenten, die den Bildrand berühren (Scan-Schatten), werden ignoriert.
3. Das Original wird auf diese Bounding-Box zugeschnitten und gespeichert.

Die Analyse läuft auf einer herunterskalierten Kopie (schnell und
speicherschonend); der Zuschnitt erfolgt anschließend in voller Auflösung.
Die Erkennung ist dadurch tolerant gegenüber Lage, Größe und leichter Schräglage
des Blatts – die drei Beispiel-Scans laufen unterschiedlich ein und werden
trotzdem korrekt erkannt.

Es wird **kein nativer Code** benötigt (kein OpenCV). Einzige Abhängigkeit ist
[Apache PDFBox](https://pdfbox.apache.org/), um PDF-Seiten zu Bildern zu rendern.

## Bauen

```bash
mvn package
```

Erzeugt das ausführbare `target/perschscan.jar` (inkl. aller Abhängigkeiten).

## Verwenden

```bash
# Einzelnes PDF oder Bild:
java -jar target/perschscan.jar scan.pdf
java -jar target/perschscan.jar blatt.jpg

# Ganzer Ordner mit Scans (PDF/PNG/JPG/TIFF/BMP/GIF):
java -jar target/perschscan.jar ./scans ./cartoons
```

* **`<eingabe>`** – eine Datei (Bild oder mehrseitiges PDF) oder ein Ordner mit
  solchen Dateien. Mehrseitige PDFs werden seitenweise verarbeitet.
* **`[ausgabeordner]`** – optionaler Zielordner. Vorgabe: `<eingabe>/cartoons`.

Die Ausgabedateien werden fortlaufend nummeriert. Bei einem erneuten Lauf in
denselben Ausgabeordner wird die Nummerierung **fortgesetzt**, vorhandene
Dateien werden nicht überschrieben.

## Hinweise

* Zugeschnitten wird exakt der umrandete Cartoon-Kasten. Bildunterschriften, die
  *außerhalb* des Rahmens unter dem Kasten stehen, gehören damit nicht zum
  Ausschnitt. (Text innerhalb des Rahmens – Sprech­blasen usw. – bleibt erhalten.)
* Wird auf einem Blatt kein plausibler Rahmen gefunden, wird die Seite mit einer
  Meldung übersprungen, statt einen falschen Ausschnitt zu speichern.
* PDF-Seiten werden mit 300 dpi gerendert.
