# qupath-extension-childstats

A [QuPath](https://qupath.github.io) extension that summarises child object
measurements (e.g. cell morphology and intensity) onto their parent
annotations. The annotation provides both a GUI and a fluent scripting
interface.

## Why

QuPath computes useful per-object measurements on cells/detections, and
useful summary statistics for display (counts, percentages, shape stats) on
annotations, but there's no built-in GUI way to roll arbitrary child
measurements up to their parent as new, persisted measurements - e.g. "mean
nucleus area of the cells in this annotation", or "total tumor area and
immune cell counts across the tumor regions in this tissue". This extension
adds that as a dialog, backed by a small scriptable API so the same
aggregation can be called from Groovy or run across a whole project.

## Features

- Pick a child object type - cells / detections / tiles / **annotations**
  (nested annotation structures, e.g. Tumor regions inside a Tissue
  annotation, are supported) - and, optionally, restrict to a specific
  classification, "Unclassified", or leave it unrestricted
- Two-pane measurement picker (available / selected) with a filter box, so
  you don't have to hunt through hundreds of measurement names. The
  available list includes both stored measurements and QuPath's dynamically
  computed ones (Area, Perimeter, Centroid, `Num <class>`, ...) - the same
  values shown in the Annotations tab's own measurement table
- Statistics: mean, std.dev., median, min, max, sum, CV, and an arbitrary
  percentile
- Configurable output name format, overwrite behaviour, and a policy for
  annotations with no valid child values (skip / write 0 / abort)
- Preview: compute and inspect values for one selected annotation without
  writing anything
- Run on the current image, or across every image in the open project
- Every run logs an equivalent, re-runnable Groovy script to QuPath's
  **Workflow** tab

## Requirements

- QuPath 0.7.0 or later

## Installation

1. Download the latest `.jar` from the
   [Releases](../../releases) page (or build it yourself - see below).
2. Drag the `.jar` onto a running QuPath window, or copy it into QuPath's
   extensions directory (**Extensions > Installed extensions... > Open
   extensions directory**).
3. Restart QuPath.

The command appears under **Extensions > Child measurement aggregator**.
It can be toggled off from **Edit > Preferences**, under the extension's own
preference category.

## Building from source

```bash
git clone https://github.com/zindy/qupath-extension-childstats.git
cd qupath-extension-childstats
./gradlew clean build
```

The built extension `.jar` is written to `build/libs/`.

## Usage

1. Open an image (or project) with annotations containing child objects
   (cells, detections, tiles, or nested annotations) that have measurements.
2. **Extensions > Child measurement aggregator**.
3. Choose the child object type and, optionally, a class filter: all
   classes, unclassified only, or a specific classification.
4. Pick which measurements to summarise and which statistics to compute.
5. Select an annotation in the viewer and click **Preview** to check the
   numbers before committing.
6. Choose to run on the current image or the whole project, then **Run**.

### Nested annotations

Child type can be set to **Annotations**, so a Tumor annotation nested
inside a Tissue annotation is a valid parent/child pair. Which annotation
counts as a "child" of which is decided entirely by QuPath's tree structure
(`getChildObjects()`), never by classification - so a Tissue annotation and
its Tumor children can share a class, or have none at all, without creating
ambiguity.

A common pattern: cells classified `CD3` / `CD8` / `Unclassified` inside
`Tumor` annotations inside a `Tissue` annotation.

1. Run the aggregator with child type **Cells**, stat **Sum**, to add
   `Num CD3`-style counts onto each Tumor (or just rely on QuPath's own
   built-in `Num <class>` summary measurement - see the note below).
2. Run it again with child type **Annotations**, class filter `Tumor`, stat
   **Sum**, on measurements `Area µm^2`, `Num CD3`, `Num CD8`,
   `Num Unclassified` - this rolls the per-Tumor totals up onto each Tissue
   annotation.

Tumor area fraction and tissue-wide immune cell densities then fall out of
those two annotation-level measurements directly (e.g. in an exported
measurement table), no scripting required.

### A note on dynamic measurements

Things like Area, Perimeter, Centroid X/Y, and `Num <class>` are not stored
on an object's measurement list - QuPath computes them on the fly for
display. This extension resolves values through
`qupath.lib.gui.measure.ObservableMeasurementTableData`, the same model
behind the Annotations tab's table, so it sees both stored and dynamic
measurements identically. The practical effect: you never need to run
"Add shape measurements" first just to make Area or Perimeter available here.

## Scripting

The dialog is a thin wrapper around `ChildMeasurementAggregator`. It has no
JavaFX Application Thread or `Stage` dependency, so it's equally callable
from a script - useful for batch processing or for chaining into a larger
pipeline - though it does need an `ImageData` passed to every call, since
resolving dynamic measurements requires that context:

```groovy
import qupath.ext.childstats.ChildMeasurementAggregator

ChildMeasurementAggregator.builder()
    .childType(qupath.lib.objects.PathCellObject.class)
    .measurements("Nucleus: Area", "Cell: Mean DAB OD")
    .stats(ChildMeasurementAggregator.Stat.MEAN, ChildMeasurementAggregator.Stat.STDEV)
    .nameFormat("%s : Annotation %s")
    .build()
    .runOn(getCurrentImageData(), getAnnotationObjects())

fireHierarchyUpdate()
```

Restricting by classification uses `ChildMeasurementAggregator.ClassFilter`,
which is an explicit three-way choice rather than a nullable `PathClass` -
`any()`, `unclassified()`, or `of(pathClass)` - since "no filter" and "only
unclassified children" are different things once children can themselves be
annotations:

```groovy
.childClass(ChildMeasurementAggregator.ClassFilter.of(getPathClass("Tumor")))
```

Every GUI run logs the exact script for that run to the Workflow tab, so you
can also just configure it once in the dialog and copy the generated script
from there.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details. 

**Transparency Notice regarding AI Generation:**
This extension was conceptually architected by a human and relies on QuPath's core APIs, but the boilerplate syntax and specific script implementations were heavily assisted by generative AI (Claude). Because AI-generated code currently resides in a legal grey area regarding human authorship, the MIT license applied here strictly covers the human arrangement, architectural choices, and integration. The code is provided strictly "AS IS" with absolutely no warranties.