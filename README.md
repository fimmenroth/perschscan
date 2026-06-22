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
3. **Cartoon-Panels** sind die großen rechteckigen Inhaltsblöcke – egal ob
   heller, umrandeter Kasten oder gefüllte (dunkle) Fläche. Die Auswahl ist
   bewusst **nicht** an Blattgröße oder Füllgrad gekoppelt: Maßstab ist der
   größte gefundene Block, und alle Blöcke in dessen Größenordnung gelten als
   Panels. Kleinere Elemente (Sprech-/Textblasen, Kalendertext, große
   Kalenderziffern) liegen deutlich darunter und fallen heraus. Da ein Blatt
   genau einen Cartoon trägt, werden **alle** Panels zu einem Bereich vereinigt –
   egal ob einzeln, gestapelt, nebeneinander oder als Raster (z. B. 2x2).
4. Eine **Bildunterschrift** direkt unter den Panels wird mit erfasst: Kleine
   Komponenten im Band unterhalb der Panels werden ausgehend von der
   Panel-Spalte horizontal verkettet. Eine größere Lücke (der Bundsteg zum
   Kalender) beendet die Verkettung, sodass der Kalender nicht hineinrutscht.
   Mehrzeilige Unterschriften werden komplett erfasst.
5. Vor dem Zuschnitt wird der Cartoon **lotrecht ausgerichtet**: Die vier Kanten
   des größten Panels werden abgetastet und per Theil-Sen (Median der paarweisen
   Steigungen) robust zu Geraden gefittet; aus den Kantensteigungen ergibt sich
   der Drehwinkel, um den die Seite gegengedreht wird (Bilinear-Interpolation,
   neue Flächen weiß). Das ist robust für umrandete wie gefüllte Panels und – im
   Gegensatz zu einem Projektionsprofil über das Pixelraster – frei von
   Raster-Aliasing bei 0°. Die Korrektur geschieht automatisch bei jedem Lauf.
6. Um das Ergebnis kommt ein **Randabstand**, dann wird das Original
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

* Cartoons werden vor dem Zuschnitt automatisch lotrecht ausgerichtet
  (Schräglage-Korrektur). Die manuelle `--rotate`-Drehung um 90° wird zuerst
  angewandt, danach die automatische Feinausrichtung.
* Mehrteilige Cartoons (mehrere Panels – gestapelt, nebeneinander oder als
  Raster wie 2x2) und Bildunterschriften unterhalb des Kastens werden mit
  zugeschnitten.
* Wird auf einem Blatt kein plausibler Cartoon gefunden, wird die Seite mit
  einer Meldung übersprungen, statt einen falschen Ausschnitt zu speichern.
* PDF-Seiten werden mit 300 dpi gerendert.
