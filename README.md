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
2. Über eine **Zusammenhangsanalyse (8er-Nachbarschaft)** werden alle dunklen
   Komponenten samt Bounding-Box bestimmt. Komponenten, die den Bildrand
   berühren (Scan-Schatten), werden ignoriert.
3. Große Komponenten in der linken Blatthälfte gelten als **Cartoon-Panels**
   (der Kalender rechts wird damit ausgeschlossen). Ein Cartoon kann aus
   **mehreren übereinander stehenden Panels** bestehen – horizontal
   überlappende Panels werden zu einem Bereich vereinigt.
4. Eine **Bildunterschrift** direkt unter den Panels wird mit erfasst: Kleine
   Komponenten im Band unterhalb der Panels werden ausgehend von der
   Panel-Spalte horizontal verkettet. Eine größere Lücke (der Bundsteg zum
   Kalender) beendet die Verkettung, sodass der Kalender nicht hineinrutscht.
   Mehrzeilige Unterschriften werden komplett erfasst.
5. Um das Ergebnis kommt ein **Randabstand**, dann wird das Original
   zugeschnitten und gespeichert.

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

# Quer eingescannte Vorlage vor der Erkennung um 90 Grad drehen:
java -jar target/perschscan.jar --rotate=cw  ./scans   # im Uhrzeigersinn
java -jar target/perschscan.jar --rotate=-90 ./scans   # gegen den Uhrzeigersinn
```

* **`--rotate=<grad>`** – optional. Dreht jede Eingabeseite vor der Erkennung um
  +90° (`90` bzw. `cw`, im Uhrzeigersinn) oder -90° (`-90`/`270` bzw. `ccw`,
  gegen den Uhrzeigersinn). Nützlich, wenn die Blätter quer eingescannt wurden.
* **`<eingabe>`** – eine Datei (Bild oder mehrseitiges PDF) oder ein Ordner mit
  solchen Dateien. Mehrseitige PDFs werden seitenweise verarbeitet.
* **`[ausgabeordner]`** – optionaler Zielordner. Vorgabe: `<eingabe>/cartoons`.

Die Ausgabedateien werden fortlaufend nummeriert. Bei einem erneuten Lauf in
denselben Ausgabeordner wird die Nummerierung **fortgesetzt**, vorhandene
Dateien werden nicht überschrieben.

## Hinweise

* Mehrteilige Cartoons (mehrere gestapelte Panels) und Bildunterschriften
  unterhalb des Kastens werden mit zugeschnitten.
* Wird auf einem Blatt kein plausibler Cartoon gefunden, wird die Seite mit
  einer Meldung übersprungen, statt einen falschen Ausschnitt zu speichern.
* PDF-Seiten werden mit 300 dpi gerendert.
