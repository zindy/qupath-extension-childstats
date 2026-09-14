# qupath-extension-childstats

A [QuPath](https://qupath.github.io) extension that summarises child object
measurements (e.g. cell morphology and intensity) onto their parent
annotations. The annotation provides both a GUI and a fluent scripting
interface.

## Why

QuPath computes useful per-object measurements on cells/detections, and
useful summary statistics for display (counts, percentages) on annotations,
but there's no built-in GUI way to roll arbitrary child measurements up to
their parent as new, persisted measurements - e.g. "mean nucleus area of the
cells in this annotation". This extension adds that as a dialog, backed by a
small scriptable API so the same aggregation can be called from Groovy or run
across a whole project.

## Features

- Pick a child object type (cells / detections / tiles) and, optionally,
  restrict to a single classification
- Two-pane measurement picker (available / selected) with a filter box, so
  you don't have to hunt through hundreds of measurement names
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
   (cells, detections, or tiles) that have measurements.
2. **Extensions > Child measurement aggregator**.
3. Choose the child object type and, optionally, a classification to
   restrict to.
4. Pick which measurements to summarise and which statistics to compute.
5. Select an annotation in the viewer and click **Preview** to check the
   numbers before committing.
6. Choose to run on the current image or the whole project, then **Run**.

## Scripting

The dialog is a thin wrapper around `ChildMeasurementAggregator`, which has
no GUI dependency and can be called directly from a script - useful for
batch processing or for chaining into a larger pipeline:

```groovy
import qupath.ext.childstats.ChildMeasurementAggregator

ChildMeasurementAggregator.builder()
    .childType(qupath.lib.objects.PathCellObject.class)
    .measurements("Nucleus: Area", "Cell: Mean DAB OD")
    .stats(ChildMeasurementAggregator.Stat.MEAN, ChildMeasurementAggregator.Stat.STDEV)
    .nameFormat("%s : Annotation %s")
    .build()
    .runOn(getAnnotationObjects())

fireHierarchyUpdate()
```

Every GUI run logs the exact script for that run to the Workflow tab, so you
can also just configure it once in the dialog and copy the generated script
from there.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details. 

**Transparency Notice regarding AI Generation:**
This extension was conceptually architected by a human and relies on QuPath's core APIs, but the boilerplate syntax and specific script implementations were heavily assisted by generative AI (Claude). Because AI-generated code currently resides in a legal grey area regarding human authorship, the MIT license applied here strictly covers the human arrangement, architectural choices, and integration. The code is provided strictly "AS IS" with absolutely no warranties.