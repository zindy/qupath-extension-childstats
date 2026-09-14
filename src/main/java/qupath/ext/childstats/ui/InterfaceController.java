package qupath.ext.childstats.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import qupath.ext.childstats.ChildMeasurementAggregator;
import qupath.ext.childstats.ChildMeasurementAggregator.AggregationResult;
import qupath.ext.childstats.ChildMeasurementAggregator.MissingPolicy;
import qupath.ext.childstats.ChildMeasurementAggregator.Stat;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathTileObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.gui.QuPathGUI;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;

/**
 * Controller for the "Child measurement aggregator" dialog.
 * <p>
 * All the actual aggregation logic lives in {@link ChildMeasurementAggregator}, which
 * has no JavaFX dependency. This class is purely responsible for turning dialog state
 * into a {@code ChildMeasurementAggregator}, and for the GUI-only concerns around that
 * (progress bar, preview table, workflow logging) - none of which apply when the same
 * aggregator is built and run from a script.
 */
public class InterfaceController extends VBox {

    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.childstats.ui.strings");

    /** Display wrapper for the child-type combo; keeps the FXML combo box simple to populate. */
    private enum ChildTypeOption {
        CELLS("Cells", PathCellObject.class),
        DETECTIONS("Detections", PathDetectionObject.class),
        TILES("Tiles", PathTileObject.class),
        ANNOTATIONS("Annotations", PathAnnotationObject.class);

        private final String label;
        private final Class<? extends PathObject> type;

        ChildTypeOption(String label, Class<? extends PathObject> type) {
            this.label = label;
            this.type = type;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @FXML private ComboBox<ChildTypeOption> childTypeCombo;
    @FXML private ComboBox<ChildMeasurementAggregator.ClassFilter> childClassCombo;

    @FXML private TextField measurementFilterField;
    @FXML private ListView<String> availableList;
    @FXML private ListView<String> selectedList;
    @FXML private Button addButton;
    @FXML private Button addAllButton;
    @FXML private Button removeButton;
    @FXML private Button clearButton;

    @FXML private CheckBox meanCheck;
    @FXML private CheckBox stdevCheck;
    @FXML private CheckBox medianCheck;
    @FXML private CheckBox minCheck;
    @FXML private CheckBox maxCheck;
    @FXML private CheckBox sumCheck;
    @FXML private CheckBox cvCheck;
    @FXML private CheckBox percentileCheck;
    @FXML private Spinner<Double> percentileSpinner;

    @FXML private ComboBox<MissingPolicy> missingPolicyCombo;
    @FXML private CheckBox overwriteCheck;
    @FXML private TextField nameFormatField;

    @FXML private RadioButton currentImageRadio;
    @FXML private RadioButton wholeProjectRadio;

    @FXML private Button previewButton;
    @FXML private TableView<AggregationResult> previewTable;
    @FXML private TableColumn<AggregationResult, String> previewMeasurementColumn;
    @FXML private TableColumn<AggregationResult, Double> previewValueColumn;
    @FXML private TableColumn<AggregationResult, Integer> previewCountColumn;

    @FXML private Button runButton;
    @FXML private ProgressBar runProgressBar;

    // backing list for the "available" side; selectedList's items are a separate list so
    // the same measurement name can be filtered out of view on the left without disturbing
    // what's already picked on the right
    private final ObservableList<String> allMeasurementNames = FXCollections.observableArrayList();
    private final ObservableList<String> selectedNames = FXCollections.observableArrayList();
    private final ObservableList<ChildMeasurementAggregator.ClassFilter> allChildClasses = FXCollections.observableArrayList();

    public static InterfaceController createInstance() throws IOException {
        return new InterfaceController();
    }

    private InterfaceController() throws IOException {
        var url = InterfaceController.class.getResource("interface.fxml");
        FXMLLoader loader = new FXMLLoader(url, resources);
        loader.setRoot(this);
        loader.setController(this);
        loader.load();

        setupChildTypeAndClass();
        setupMeasurementPicker();
        setupStats();
        setupTooltips();
        setupMissingPolicy();
        setupPreviewTable();
        setupScope();

        nameFormatField.setText("%s : Annotation %s");

        // repopulate available measurements/classes whenever the child type changes,
        // since "Area" on a cell and "Area" on a tile are not the same underlying set
        childTypeCombo.valueProperty().addListener((obs, oldVal, newVal) -> refreshFromCurrentImage());
        refreshFromCurrentImage();
    }

    private void setupChildTypeAndClass() {
        childTypeCombo.setItems(FXCollections.observableArrayList(ChildTypeOption.values()));
        childTypeCombo.getSelectionModel().select(ChildTypeOption.CELLS);
        childTypeCombo.setTooltip(new javafx.scene.control.Tooltip(resources.getString("tooltip.childType")));

        // ClassFilter.toString() already gives the right label ("(All classes)", "(Unclassified)",
        // or the class name), so no custom StringConverter is needed here.
        childClassCombo.setItems(allChildClasses);
        childClassCombo.getSelectionModel().selectFirst(); // ClassFilter.any() is always index 0
    }

    private void setupMeasurementPicker() {
        availableList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        selectedList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        selectedList.setItems(selectedNames);

        var filteredAvailable = new FilteredList<>(allMeasurementNames, n -> true);
        availableList.setItems(filteredAvailable);
        measurementFilterField.textProperty().addListener((obs, oldVal, newVal) -> {
            var needle = newVal == null ? "" : newVal.toLowerCase();
            filteredAvailable.setPredicate(n -> n.toLowerCase().contains(needle));
        });

        addButton.setOnAction(e -> {
            for (var name : List.copyOf(availableList.getSelectionModel().getSelectedItems())) {
                if (!selectedNames.contains(name))
                    selectedNames.add(name);
            }
        });
        addAllButton.setOnAction(e -> {
            for (var name : List.copyOf(filteredAvailable)) {
                if (!selectedNames.contains(name))
                    selectedNames.add(name);
            }
        });
        removeButton.setOnAction(e ->
                selectedNames.removeAll(List.copyOf(selectedList.getSelectionModel().getSelectedItems())));
        clearButton.setOnAction(e -> selectedNames.clear());
    }

    private void setupStats() {
        percentileSpinner.disableProperty().bind(percentileCheck.selectedProperty().not());
    }

    private void setupTooltips() {
        missingPolicyCombo.setTooltip(new javafx.scene.control.Tooltip(
                resources.getString("tooltip.missing")));
        nameFormatField.setTooltip(new javafx.scene.control.Tooltip(
                resources.getString("tooltip.nameFormat")));
        overwriteCheck.setTooltip(new javafx.scene.control.Tooltip(
                resources.getString("tooltip.overwrite")));
    }

    private void setupMissingPolicy() {
        missingPolicyCombo.setItems(FXCollections.observableArrayList(MissingPolicy.values()));
        missingPolicyCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(MissingPolicy p) {
                if (p == null) return "";
                return switch (p) {
                    case SKIP -> resources.getString("missing.skip");
                    case ZERO -> resources.getString("missing.zero");
                    case ABORT -> resources.getString("missing.abort");
                };
            }

            @Override
            public MissingPolicy fromString(String s) {
                return null; // not editable
            }
        });
        missingPolicyCombo.getSelectionModel().select(MissingPolicy.SKIP);
    }

    @SuppressWarnings("unchecked")
    private void setupPreviewTable() {
        previewMeasurementColumn.setCellValueFactory(new PropertyValueFactory<>("measurementName"));
        previewValueColumn.setCellValueFactory(new PropertyValueFactory<>("value"));
        previewCountColumn.setCellValueFactory(new PropertyValueFactory<>("childCount"));
    }

    private void setupScope() {
        // Whole-project runs need... a project. If there isn't one open, don't offer it.
        var qupath = QuPathGUI.getInstance();
        boolean hasProject = qupath != null && qupath.getProject() != null;
        wholeProjectRadio.setDisable(!hasProject);
    }

    /** Re-reads measurement names and classes present on children of the selected type, in the current image. */
    private void refreshFromCurrentImage() {
        var imageData = currentImageData();
        var names = new TreeSet<String>();
        var specificClasses = new TreeSet<PathClass>(java.util.Comparator.comparing(PathClass::toString));
        var matchingChildren = new ArrayList<PathObject>();

        if (imageData != null) {
            var childType = selectedChildType();
            for (var annotation : imageData.getHierarchy().getAnnotationObjects()) {
                for (var child : annotation.getChildObjects()) {
                    if (!childType.isInstance(child))
                        continue;
                    matchingChildren.add(child);
                    var pc = child.getPathClass();
                    if (pc != null)
                        specificClasses.add(pc);
                }
            }
            if (!matchingChildren.isEmpty()) {
                // Same lookup ChildMeasurementAggregator itself uses at run time, so what's
                // offered here always matches what will actually resolve to a number - including
                // dynamically computed measurements (Area, Perimeter, Num <class>, ...) that
                // getMeasurementList() alone would never surface.
                var table = new ObservableMeasurementTableData();
                table.setImageData(imageData, matchingChildren);
                names.addAll(table.getMeasurementNames());
            }
        }

        var classFilters = new ArrayList<ChildMeasurementAggregator.ClassFilter>();
        classFilters.add(ChildMeasurementAggregator.ClassFilter.any());
        classFilters.add(ChildMeasurementAggregator.ClassFilter.unclassified());
        specificClasses.forEach(pc -> classFilters.add(ChildMeasurementAggregator.ClassFilter.of(pc)));

        allMeasurementNames.setAll(names);
        allChildClasses.setAll(classFilters);
        childClassCombo.getSelectionModel().selectFirst();
        // drop any previously-selected measurements that no longer exist for this child type
        selectedNames.retainAll(names);
    }

    private Class<? extends PathObject> selectedChildType() {
        var option = childTypeCombo.getValue();
        return option == null ? PathCellObject.class : option.type;
    }

    private ImageData<BufferedImage> currentImageData() {
        var qupath = QuPathGUI.getInstance();
        return qupath == null ? null : qupath.getImageData();
    }

    @FXML
    private void runPreview() {
        var imageData = currentImageData();
        if (imageData == null) {
            Dialogs.showWarningNotification(resources.getString("run.title"), resources.getString("warning.no.image"));
            return;
        }
        var selected = imageData.getHierarchy().getSelectionModel().getSelectedObject();
        if (!(selected instanceof PathAnnotationObject)) {
            Dialogs.showWarningNotification(resources.getString("run.title"), resources.getString("warning.no.selection"));
            return;
        }

        ChildMeasurementAggregator aggregator;
        try {
            aggregator = buildAggregator();
        } catch (IllegalStateException ex) {
            Dialogs.showWarningNotification(resources.getString("run.title"), ex.getMessage());
            return;
        }

        previewTable.getItems().setAll(aggregator.preview(imageData, selected));
    }

    @FXML
    private void runAggregation() {
        ChildMeasurementAggregator aggregator;
        try {
            aggregator = buildAggregator();
        } catch (IllegalStateException ex) {
            Dialogs.showWarningNotification(resources.getString("run.title"), ex.getMessage());
            return;
        }

        if (wholeProjectRadio.isSelected()) {
            runOnWholeProject(aggregator);
        } else {
            runOnCurrentImage(aggregator);
        }
    }

    private void runOnCurrentImage(ChildMeasurementAggregator aggregator) {
        var imageData = currentImageData();
        if (imageData == null) {
            Dialogs.showWarningNotification(resources.getString("run.title"), resources.getString("warning.no.image"));
            return;
        }
        var hierarchy = imageData.getHierarchy();
        var annotations = hierarchy.getAnnotationObjects();

        setRunning(true);
        var task = new Task<Void>() {
            @Override
            protected Void call() {
                aggregator.runOn(imageData, annotations, done -> updateProgress(done, annotations.size()));
                return null;
            }
        };
        runProgressBar.progressProperty().bind(task.progressProperty());
        task.setOnSucceeded(e -> {
            setRunning(false);
            hierarchy.fireHierarchyChangedEvent(this);
            logToWorkflow(imageData, aggregator);
            Dialogs.showInfoNotification(resources.getString("run.title"),
                    String.format(resources.getString("run.done"), annotations.size()));
        });
        task.setOnFailed(e -> {
            setRunning(false);
            Dialogs.showErrorMessage(resources.getString("run.title"), task.getException());
        });
        // Plain daemon thread to keep this sample dependency-free; if QuPathGUI exposes a
        // shared executor in your 0.7 build, prefer submitting there instead of a raw Thread.
        var thread = new Thread(task, "childstats-aggregate");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Whole-project run: reads, processes, and re-saves every project image in turn.
     * Verify {@code readImageData()}/{@code saveImageData()} against your QuPath 0.7 API -
     * project I/O methods have shifted slightly across versions.
     */
    private void runOnWholeProject(ChildMeasurementAggregator aggregator) {
        var qupath = QuPathGUI.getInstance();
        Project<BufferedImage> project = qupath == null ? null : qupath.getProject();
        if (project == null) {
            Dialogs.showWarningNotification(resources.getString("run.title"), resources.getString("warning.no.image"));
            return;
        }
        List<ProjectImageEntry<BufferedImage>> entries = new ArrayList<>(project.getImageList());

        setRunning(true);
        var task = new Task<Void>() {
            @Override
            protected Void call() {
                for (int i = 0; i < entries.size(); i++) {
                    var entry = entries.get(i);
                    try {
                        ImageData<BufferedImage> imageData = entry.readImageData();
                        var annotations = imageData.getHierarchy().getAnnotationObjects();
                        aggregator.runOn(imageData, annotations);
                        imageData.getHierarchy().fireHierarchyChangedEvent(this);
                        logToWorkflow(imageData, aggregator);
                        entry.saveImageData(imageData);
                    } catch (IOException ex) {
                        // per-image failure shouldn't abort the whole project run
                        Platform.runLater(() -> Dialogs.showErrorMessage(
                                resources.getString("run.title"),
                                entry.getImageName() + ": " + ex.getMessage()));
                    }
                    updateProgress(i + 1, entries.size());
                }
                return null;
            }
        };
        runProgressBar.progressProperty().bind(task.progressProperty());
        task.setOnSucceeded(e -> {
            setRunning(false);
            Dialogs.showInfoNotification(resources.getString("run.title"),
                    String.format(resources.getString("run.done"), entries.size()));
        });
        task.setOnFailed(e -> {
            setRunning(false);
            Dialogs.showErrorMessage(resources.getString("run.title"), task.getException());
        });
        var thread = new Thread(task, "childstats-aggregate-project");
        thread.setDaemon(true);
        thread.start();
    }

    private void logToWorkflow(ImageData<BufferedImage> imageData, ChildMeasurementAggregator aggregator) {
        imageData.getHistoryWorkflow().addStep(
                new DefaultScriptableWorkflowStep(resources.getString("run.title"), aggregator.toScriptString()));
    }

    private void setRunning(boolean running) {
        runButton.setDisable(running);
        previewButton.setDisable(running);
        runProgressBar.setVisible(running);
        if (!running)
            runProgressBar.progressProperty().unbind();
    }

    private ChildMeasurementAggregator buildAggregator() {
        if (selectedNames.isEmpty())
            throw new IllegalStateException(resources.getString("warning.no.measurements"));

        Set<Stat> stats = new LinkedHashSet<>();
        if (meanCheck.isSelected()) stats.add(Stat.MEAN);
        if (stdevCheck.isSelected()) stats.add(Stat.STDEV);
        if (medianCheck.isSelected()) stats.add(Stat.MEDIAN);
        if (minCheck.isSelected()) stats.add(Stat.MIN);
        if (maxCheck.isSelected()) stats.add(Stat.MAX);
        if (sumCheck.isSelected()) stats.add(Stat.SUM);
        if (cvCheck.isSelected()) stats.add(Stat.CV);
        if (percentileCheck.isSelected()) stats.add(Stat.PERCENTILE);
        if (stats.isEmpty())
            throw new IllegalStateException(resources.getString("warning.no.stats"));

        var builder = ChildMeasurementAggregator.builder()
                .childType(selectedChildType())
                .childClass(childClassCombo.getValue())
                .measurements(selectedNames)
                .stats(stats.toArray(new Stat[0]))
                .nameFormat(nameFormatField.getText())
                .overwrite(overwriteCheck.isSelected())
                .onMissing(missingPolicyCombo.getValue());

        if (percentileCheck.isSelected())
            builder.percentile(percentileSpinner.getValue());

        return builder.build();
    }
}
