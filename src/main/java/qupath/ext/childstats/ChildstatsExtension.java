package qupath.ext.childstats;

import javafx.beans.property.BooleanProperty;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.childstats.ui.InterfaceController;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;

import java.io.IOException;
import java.util.ResourceBundle;

/**
 * Adds a "Child measurement aggregator" command under Extensions, which summarises
 * child object measurements (e.g. cell morphology/intensity) onto their parent
 * annotations - as new, named measurements - without needing a script.
 * <p>
 * The dialog is a thin wrapper: all aggregation logic lives in
 * {@link ChildMeasurementAggregator}, which has no GUI dependency and is equally
 * callable from a script (see {@link ChildMeasurementAggregator#toScriptString()},
 * which is also what gets logged to the Workflow panel after a GUI run).
 */
public class ChildstatsExtension implements QuPathExtension {

	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.childstats.ui.strings");
	private static final Logger logger = LoggerFactory.getLogger(ChildstatsExtension.class);

	private static final String EXTENSION_NAME = resources.getString("name");
	private static final String EXTENSION_DESCRIPTION = resources.getString("description");

	// Targeting QuPath 0.7+
	private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.7.0");

	private boolean isInstalled = false;

	/** Persistent preference controlling whether the menu item is enabled. */
	private static final BooleanProperty enableExtensionProperty = PathPrefs.createPersistentPreference(
			"childstats.enable", true);

	private Stage stage;

	@Override
	public void installExtension(QuPathGUI qupath) {
		if (isInstalled) {
			logger.debug("{} is already installed", getName());
			return;
		}
		isInstalled = true;
		addPreferenceToPane(qupath);
		addMenuItem(qupath);
	}

	private void addPreferenceToPane(QuPathGUI qupath) {
		var propertyItem = new PropertyItemBuilder<>(enableExtensionProperty, Boolean.class)
				.name(resources.getString("menu.enable"))
				.category(EXTENSION_NAME)
				.description(resources.getString("menu.enable"))
				.build();
		qupath.getPreferencePane()
				.getPropertySheet()
				.getItems()
				.add(propertyItem);
	}

	private void addMenuItem(QuPathGUI qupath) {
		var menu = qupath.getMenu("Extensions", true);
		MenuItem menuItem = new MenuItem(EXTENSION_NAME);
		menuItem.setOnAction(e -> createStage());
		menuItem.disableProperty().bind(enableExtensionProperty.not());
		menu.getItems().add(menuItem);
	}

	private void createStage() {
		if (stage == null) {
			try {
				stage = new Stage();
				Scene scene = new Scene(InterfaceController.createInstance());
				stage.initOwner(QuPathGUI.getInstance().getStage());
				stage.setTitle(resources.getString("stage.title"));
				stage.setScene(scene);
				stage.setResizable(true);
			} catch (IOException e) {
				Dialogs.showErrorMessage(resources.getString("error"), resources.getString("error.gui-loading-failed"));
				logger.error("Unable to load extension interface FXML", e);
			}
		}
		stage.show();
	}

	@Override
	public String getName() {
		return EXTENSION_NAME;
	}

	@Override
	public String getDescription() {
		return EXTENSION_DESCRIPTION;
	}

	@Override
	public Version getQuPathVersion() {
		return EXTENSION_QUPATH_VERSION;
	}
}
