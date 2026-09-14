package qupath.ext.childstats;

import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

/**
 * Aggregates measurements from a parent object's children (e.g. a cell's
 * measurements rolled up to its parent annotation) and writes the results
 * as new measurements on the parent.
 * <p>
 * Deliberately has no GUI or JavaFX dependency: it is safe to call from a
 * script, a headless batch job, or the extension's dialog. Progress
 * reporting, if wanted, is injected via an {@link IntConsumer} rather than
 * baked in, so GUI code can wrap a run in a progress dialog while script
 * code can simply omit it.
 * <p>
 * Callers are responsible for firing a hierarchy update afterwards
 * (e.g. {@code fireHierarchyUpdate()} from a script, or
 * {@code hierarchy.fireHierarchyChangedEvent(this)} from Java) - this class
 * does not assume there is a "current" hierarchy, since it may be applied
 * across several images in a project run.
 */
public class ChildMeasurementAggregator {

    /** Statistic to compute across a set of child measurement values. */
    public enum Stat {
        MEAN, STDEV, MEDIAN, MIN, MAX, SUM, CV, PERCENTILE
    }

    /** How to treat a parent with zero qualifying child values for a given measurement. */
    public enum MissingPolicy {
        /** Don't write a measurement at all. */
        SKIP,
        /** Write 0.0. */
        ZERO,
        /** Throw, aborting the whole run. */
        ABORT
    }

    private final Class<? extends PathObject> childType;
    private final PathClass childClass; // null = any class
    private final List<String> measurementNames;
    private final Set<Stat> stats;
    private final double percentile; // used only if stats contains PERCENTILE, in [0, 100]
    private final String nameFormat; // formatted with (measurementName, statLabel)
    private final boolean overwrite;
    private final MissingPolicy missingPolicy;

    private ChildMeasurementAggregator(Builder b) {
        this.childType = b.childType;
        this.childClass = b.childClass;
        this.measurementNames = List.copyOf(b.measurementNames);
        this.stats = Set.copyOf(b.stats);
        this.percentile = b.percentile;
        this.nameFormat = b.nameFormat;
        this.overwrite = b.overwrite;
        this.missingPolicy = b.missingPolicy;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Compute aggregate measurements for a single parent object and write them
     * to its measurement list.
     *
     * @param parent the object whose children will be summarised
     * @return one result per (measurement, stat) pair that was actually written;
     *         empty if the parent has no qualifying children
     */
    public List<AggregationResult> apply(PathObject parent) {
        return compute(parent, true);
    }

    /**
     * Compute aggregate measurements for a single parent object <i>without</i>
     * writing anything - for the dialog's "Preview" action, so a user can check
     * the numbers before committing to a run that mutates every annotation.
     *
     * @param parent the object whose children will be summarised
     * @return one result per (measurement, stat) pair that would be written
     */
    public List<AggregationResult> preview(PathObject parent) {
        return compute(parent, false);
    }

    private List<AggregationResult> compute(PathObject parent, boolean write) {
        var children = parent.getChildObjects().stream()
                .filter(childType::isInstance)
                .filter(c -> childClass == null || childClass.equals(c.getPathClass()))
                .toList();

        var results = new ArrayList<AggregationResult>();
        if (children.isEmpty())
            return results;

        var parentMeasurements = parent.getMeasurementList();

        for (var measurementName : measurementNames) {
            var values = children.stream()
                    .mapToDouble(c -> c.getMeasurementList().get(measurementName))
                    .filter(v -> !Double.isNaN(v) && !Double.isInfinite(v))
                    .toArray();

            for (var stat : stats) {
                double value;
                if (values.length == 0) {
                    switch (missingPolicy) {
                        case SKIP:
                            continue;
                        case ZERO:
                            value = 0.0;
                            break;
                        case ABORT:
                            throw new IllegalStateException(
                                    "No valid values for '" + measurementName + "' on " + parent);
                        default:
                            throw new IllegalStateException("Unhandled MissingPolicy: " + missingPolicy);
                    }
                } else {
                    value = compute(stat, values);
                }

                var outputName = String.format(nameFormat, measurementName, statLabel(stat));
                if (!overwrite && !Double.isNaN(parentMeasurements.get(outputName))) {
                    continue;
                }
                if (write)
                    parentMeasurements.put(outputName, value);
                results.add(new AggregationResult(outputName, value, values.length));
            }
        }
        return results;
    }

    /**
     * Run over several parent objects.
     *
     * @param parents  objects to process
     * @param progress optional callback invoked with the number of parents processed
     *                 so far (1-indexed); may be {@code null}
     */
    public void runOn(Collection<? extends PathObject> parents, IntConsumer progress) {
        int done = 0;
        for (var parent : parents) {
            apply(parent);
            done++;
            if (progress != null)
                progress.accept(done);
        }
    }

    public void runOn(Collection<? extends PathObject> parents) {
        runOn(parents, null);
    }

    private double compute(Stat stat, double[] values) {
        switch (stat) {
            case MEAN:
                return Arrays.stream(values).average().orElse(Double.NaN);
            case SUM:
                return Arrays.stream(values).sum();
            case MIN:
                return Arrays.stream(values).min().orElse(Double.NaN);
            case MAX:
                return Arrays.stream(values).max().orElse(Double.NaN);
            case MEDIAN:
                return percentileOf(values, 50);
            case PERCENTILE:
                return percentileOf(values, percentile);
            case STDEV:
                return stdev(values);
            case CV: {
                double mean = Arrays.stream(values).average().orElse(Double.NaN);
                return mean == 0.0 ? Double.NaN : stdev(values) / mean;
            }
            default:
                throw new IllegalStateException("Unhandled Stat: " + stat);
        }
    }

    /** Sample standard deviation; 0 for n=1, matching the convention in the original script. */
    private static double stdev(double[] values) {
        if (values.length < 2)
            return 0.0;
        double mean = Arrays.stream(values).average().orElse(0.0);
        double variance = Arrays.stream(values).map(v -> (v - mean) * (v - mean)).sum() / (values.length - 1);
        return Math.sqrt(variance);
    }

    /** Linear-interpolation percentile (same convention as e.g. numpy's default). */
    private static double percentileOf(double[] values, double pct) {
        var sorted = values.clone();
        Arrays.sort(sorted);
        if (sorted.length == 1)
            return sorted[0];
        double rank = (pct / 100.0) * (sorted.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi)
            return sorted[lo];
        double frac = rank - lo;
        return sorted[lo] * (1 - frac) + sorted[hi] * frac;
    }

    private String statLabel(Stat stat) {
        switch (stat) {
            case MEAN: return "Mean";
            case STDEV: return "Std.dev.";
            case MEDIAN: return "Median";
            case MIN: return "Min";
            case MAX: return "Max";
            case SUM: return "Sum";
            case CV: return "CV";
            case PERCENTILE:
                return "P" + (percentile == Math.floor(percentile) ? String.valueOf((int) percentile) : String.valueOf(percentile));
            default:
                throw new IllegalStateException("Unhandled Stat: " + stat);
        }
    }

    /**
     * Render this configuration as a standalone Groovy script - used both to log an
     * equivalent, re-runnable step to the Workflow panel, and for a "copy as script"
     * affordance in the dialog. Fully-qualified names throughout, so it runs without
     * relying on QuPath's default script imports.
     */
    public String toScriptString() {
        var sb = new StringBuilder();
        sb.append("qupath.ext.childstats.ChildMeasurementAggregator.builder()\n");
        sb.append("    .childType(").append(childType.getName()).append(".class)\n");
        if (childClass != null)
            sb.append("    .childClass(qupath.lib.objects.classes.PathClass.fromString(\"")
                    .append(childClass.toString().replace("\"", "\\\"")).append("\"))\n");
        sb.append("    .measurements(")
                .append(measurementNames.stream()
                        .map(n -> "\"" + n.replace("\"", "\\\"") + "\"")
                        .collect(Collectors.joining(", ")))
                .append(")\n");
        sb.append("    .stats(")
                .append(stats.stream()
                        .map(s -> "qupath.ext.childstats.ChildMeasurementAggregator.Stat." + s)
                        .collect(Collectors.joining(", ")))
                .append(")\n");
        if (stats.contains(Stat.PERCENTILE))
            sb.append("    .percentile(").append(percentile).append(")\n");
        sb.append("    .nameFormat(\"").append(nameFormat.replace("\"", "\\\"")).append("\")\n");
        sb.append("    .overwrite(").append(overwrite).append(")\n");
        sb.append("    .onMissing(qupath.ext.childstats.ChildMeasurementAggregator.MissingPolicy.")
                .append(missingPolicy).append(")\n");
        sb.append("    .build()\n");
        sb.append("    .runOn(getAnnotationObjects())\n");
        sb.append("\nfireHierarchyUpdate()");
        return sb.toString();
    }

    /** One computed (measurement, stat) result - used to populate the preview table. */
    public static final class AggregationResult {
        private final String measurementName;
        private final double value;
        private final int childCount;

        public AggregationResult(String measurementName, double value, int childCount) {
            this.measurementName = measurementName;
            this.value = value;
            this.childCount = childCount;
        }

        public String getMeasurementName() { return measurementName; }
        public double getValue() { return value; }
        public int getChildCount() { return childCount; }
    }

    public static final class Builder {
        private Class<? extends PathObject> childType = PathCellObject.class;
        private PathClass childClass = null;
        private final List<String> measurementNames = new ArrayList<>();
        private final Set<Stat> stats = EnumSet.noneOf(Stat.class);
        private double percentile = 90.0;
        private String nameFormat = "%s : Annotation %s";
        private boolean overwrite = true;
        private MissingPolicy missingPolicy = MissingPolicy.SKIP;

        public Builder childType(Class<? extends PathObject> childType) {
            this.childType = Objects.requireNonNull(childType);
            return this;
        }

        /** Restrict to children with this exact classification; {@code null} (the default) means any class. */
        public Builder childClass(PathClass childClass) {
            this.childClass = childClass;
            return this;
        }

        public Builder measurements(String... names) {
            return measurements(Arrays.asList(names));
        }

        public Builder measurements(Collection<String> names) {
            this.measurementNames.clear();
            this.measurementNames.addAll(names);
            return this;
        }

        public Builder stats(Stat... stats) {
            this.stats.clear();
            this.stats.addAll(Arrays.asList(stats));
            return this;
        }

        /** Percentile in [0, 100]; only relevant if {@link Stat#PERCENTILE} is included. Default 90. */
        public Builder percentile(double percentile) {
            if (percentile < 0 || percentile > 100)
                throw new IllegalArgumentException("Percentile must be in [0, 100], was " + percentile);
            this.percentile = percentile;
            return this;
        }

        /** Format string with two {@code %s} placeholders: (measurement name, stat label). */
        public Builder nameFormat(String nameFormat) {
            this.nameFormat = Objects.requireNonNull(nameFormat);
            return this;
        }

        public Builder overwrite(boolean overwrite) {
            this.overwrite = overwrite;
            return this;
        }

        public Builder onMissing(MissingPolicy policy) {
            this.missingPolicy = Objects.requireNonNull(policy);
            return this;
        }

        public ChildMeasurementAggregator build() {
            if (measurementNames.isEmpty())
                throw new IllegalStateException("At least one measurement must be selected");
            if (stats.isEmpty())
                throw new IllegalStateException("At least one statistic must be selected");
            return new ChildMeasurementAggregator(this);
        }
    }
}
