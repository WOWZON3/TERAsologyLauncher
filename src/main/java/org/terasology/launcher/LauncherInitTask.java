/*
 * Copyright 2016 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.terasology.launcher;

import io.vavr.control.Option;
import io.vavr.control.Try;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.launcher.github.GitHubRelease;
import org.terasology.launcher.packages.PackageManager;
import org.terasology.launcher.settings.BaseLauncherSettings;
import org.terasology.launcher.settings.LauncherSettingsValidator;
import org.terasology.launcher.updater.LauncherUpdater;
import org.terasology.launcher.util.BundleUtils;
import org.terasology.launcher.util.DirectoryCreator;
import org.terasology.launcher.util.FileUtils;
import org.terasology.launcher.util.GuiUtils;
import org.terasology.launcher.util.HostServicesWrapper;
import org.terasology.launcher.util.LauncherDirectoryUtils;
import org.terasology.launcher.util.LauncherManagedDirectory;
import org.terasology.launcher.util.LauncherStartFailedException;
import org.terasology.launcher.util.OperatingSystem;
import org.terasology.launcher.version.TerasologyLauncherVersionInfo;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

public class LauncherInitTask extends Task<LauncherConfiguration> {

    private static final Logger logger = LoggerFactory.getLogger(LauncherInitTask.class);

    private final Stage owner;
    private final HostServicesWrapper hostServices;

    public LauncherInitTask(final Stage newOwner, HostServicesWrapper hostServices) {
        this.owner = newOwner;
        this.hostServices = hostServices;
    }

    /**
     * Assembles a {@link LauncherConfiguration}.
     *
     * @return a complete launcher configuration or {@code null} if the initialization failed
     */
    @Override
    protected LauncherConfiguration call() {
        try {
            // get OS info
            final OperatingSystem os = getOperatingSystem();

            // init directories
            updateMessage(BundleUtils.getLabel("splash_initLauncherDirs"));
            final Path launcherDirectory = getLauncherDirectory(os);

            final Path downloadDirectory = getDirectoryFor(org.terasology.launcher.util.LauncherManagedDirectory.DOWNLOAD, launcherDirectory);
            final Path tempDirectory = getDirectoryFor(org.terasology.launcher.util.LauncherManagedDirectory.TEMP, launcherDirectory);
            final Path cacheDirectory = getDirectoryFor(org.terasology.launcher.util.LauncherManagedDirectory.CACHE, launcherDirectory);

            // launcher settings
            final BaseLauncherSettings launcherSettings = getLauncherSettings(launcherDirectory);

            // validate the settings
            LauncherSettingsValidator.validate(launcherSettings);

            if (launcherSettings.isSearchForLauncherUpdates()) {
                final Path targetDirectory = launcherSettings.isKeepDownloadedFiles() ? downloadDirectory : tempDirectory;
                final boolean selfUpdaterStarted =
                        checkForLauncherUpdates(targetDirectory, tempDirectory);
                if (selfUpdaterStarted) {
                    logger.info("Exit old TerasologyLauncher: {}", TerasologyLauncherVersionInfo.getInstance());
                    return NullLauncherConfiguration.getInstance();
                }
            }

            // game directories
            logger.info("Initializing game directories");
            updateMessage(BundleUtils.getLabel("splash_initGameDirs"));
            final Path gameDirectory = getGameDirectory(os, launcherSettings.getGameDirectory());
            final Path gameDataDirectory = getGameDataDirectory(os, launcherSettings.getGameDataDirectory());

            // TODO: Does this interact with any remote server for fetching/initializing the database?
            logger.trace("Setting up Package Manager");
            final PackageManager packageManager = new PackageManager();
            packageManager.initLocalStorage(gameDirectory, cacheDirectory);
            packageManager.initDatabase(launcherDirectory, gameDirectory);
            packageManager.syncDatabase();

            logger.trace("Change LauncherSettings...");
            launcherSettings.setGameDirectory(gameDirectory);
            launcherSettings.setGameDataDirectory(gameDataDirectory);
            // TODO: Rewrite gameVersions.fixSettingsBuildVersion(launcherSettings);

            storeLauncherSettingsAfterInit(launcherSettings);

            logger.trace("Creating launcher frame...");

            return new LauncherConfiguration(launcherDirectory, downloadDirectory, tempDirectory, cacheDirectory, launcherSettings, packageManager);
        } catch (LauncherStartFailedException e) {
            logger.warn("Could not configure launcher.");
        }
        return null;
    }

    private OperatingSystem getOperatingSystem() throws LauncherStartFailedException {
        logger.trace("Init OperatingSystem...");
        updateMessage(BundleUtils.getLabel("splash_checkOS"));
        final OperatingSystem os = OperatingSystem.getOS();
        if (os == OperatingSystem.UNKNOWN) {
            logger.error("The operating system is not supported! '{}' '{}' '{}'", System.getProperty("os.name"), System.getProperty("os.arch"),
                    System.getProperty("os.version"));
            GuiUtils.showErrorMessageDialog(owner, BundleUtils.getLabel("message_error_operatingSystem"));
            throw new LauncherStartFailedException();
        }
        logger.debug("Operating system: {}", os);
        return os;
    }

    private void initDirectory(Path dir, String errorLabel, DirectoryCreator... creators)
            throws LauncherStartFailedException {
        try {
            for (DirectoryCreator creator : creators) {
                creator.apply(dir);
            }
        } catch (IOException e) {
            logger.error("Directory '{}' cannot be created or used! '{}'", dir.getFileName(), dir, e);
            GuiUtils.showErrorMessageDialog(owner, BundleUtils.getLabel(errorLabel) + "\n" + dir);
            throw new LauncherStartFailedException();
        }
        logger.debug("{} directory: {}", dir.getFileName(), dir);
    }

    private Path getDirectoryFor(LauncherManagedDirectory directoryType, Path launcherDirectory)
            throws LauncherStartFailedException {

        Path dir = directoryType.getDirectoryPath(launcherDirectory);

        initDirectory(dir, directoryType.getErrorLabel(), directoryType.getCreators());
        return dir;
    }

    private Path getLauncherDirectory(OperatingSystem os) throws LauncherStartFailedException {
        final Path launcherDirectory =
                LauncherDirectoryUtils.getApplicationDirectory(os, LauncherDirectoryUtils.LAUNCHER_APPLICATION_DIR_NAME);
        initDirectory(launcherDirectory, "message_error_launcherDirectory", FileUtils::ensureWritableDir);
        return launcherDirectory;
    }

    private BaseLauncherSettings getLauncherSettings(Path launcherDirectory) throws LauncherStartFailedException {
        logger.trace("Init LauncherSettings...");
        updateMessage(BundleUtils.getLabel("splash_retrieveLauncherSettings"));
        final BaseLauncherSettings launcherSettings = new BaseLauncherSettings(launcherDirectory);
        try {
            launcherSettings.load();
            launcherSettings.init();
        } catch (IOException e) {
            logger.error("The launcher settings can not be loaded or initialized! '{}'", launcherSettings.getLauncherSettingsFilePath(), e);
            GuiUtils.showErrorMessageDialog(owner, BundleUtils.getLabel("message_error_loadSettings") + "\n" + launcherSettings.getLauncherSettingsFilePath());
            throw new LauncherStartFailedException();
        }
        logger.debug("Launcher Settings: {}", launcherSettings);
        return launcherSettings;
    }

    /**
     * Check for and trigger an update if a newer release is available and the user confirms the update.
     *
     * @param downloadDirectory where to download the launcher archive to
     * @param tempDirectory     where to extract the launcher archive for the self-update process
     * @return true if the self-updater is started for an update, false otherwise
     */
    private boolean checkForLauncherUpdates(final Path downloadDirectory, final Path tempDirectory) {
        logger.trace("Check for launcher updates...");
        updateMessage(BundleUtils.getLabel("splash_launcherUpdateCheck"));
        final LauncherUpdater updater = new LauncherUpdater(TerasologyLauncherVersionInfo.getInstance());
        final Option<GitHubRelease> latestRelease = updater.updateAvailable();

        //TODO: more functional implementation
        if (latestRelease.isDefined()) {
            final GitHubRelease release = latestRelease.get();
            logger.trace("Launcher update available!");
            updateMessage(BundleUtils.getLabel("splash_launcherUpdateAvailable"));

            final Try<Path> installationDirectory = updater.detectAndCheckLauncherInstallationDirectory()
                    .onFailure(e -> {
                        logger.error("The launcher installation directory can not be detected or used!", e);
                        GuiUtils.showErrorMessageDialog(owner, BundleUtils.getLabel("message_error_launcherInstallationDirectory"));
                    });

            installationDirectory
                    //TODO: .filter(directory -> updater.showUpdateDialog(owner, directory, release))
                    .map(directory -> updater.showUpdateDialog(owner, directory, release))
                    //TODO: .map(directory -> updater.update(downloadDirectory, tempDirectory, directory, release))
                    //TODO: .getOrElse(false);
                    .onSuccess(this::showDownloadPage);
        }
        return false;
    }

    private void showDownloadPage(final boolean _ignored) {
        final String downloadPage = "https://terasology.org/download";
        try {
            hostServices.tryOpenUri(new URI(downloadPage));
        } catch (URISyntaxException e) {
            logger.warn("Could not open URL '{}': {}", downloadPage, e.getMessage());
        }
    }

    private Path getGameDirectory(OperatingSystem os, Path settingsGameDirectory) throws LauncherStartFailedException {
        logger.trace("Init GameDirectory...");
        Path gameDirectory = settingsGameDirectory;
        if (gameDirectory != null) {
            try {
                FileUtils.ensureWritableDir(gameDirectory);
            } catch (IOException e) {
                logger.warn("The game directory can not be created or used! '{}'", gameDirectory, e);
                GuiUtils.showWarningMessageDialog(owner, BundleUtils.getLabel("message_error_gameDirectory") + "\n" + gameDirectory);

                // Set gameDirectory to 'null' -> user has to choose new game directory
                gameDirectory = null;
            }
        }
        if (gameDirectory == null) {
            logger.trace("Choose installation directory for the game...");
            updateMessage(BundleUtils.getLabel("splash_chooseGameDirectory"));
            gameDirectory = GuiUtils.chooseDirectoryDialog(owner, LauncherDirectoryUtils.getApplicationDirectory(os, LauncherDirectoryUtils.GAME_APPLICATION_DIR_NAME),
                    BundleUtils.getLabel("message_dialog_title_chooseGameDirectory"));
            if (gameDirectory == null || Files.notExists(gameDirectory)) {
                logger.info("The new game directory is not approved. The TerasologyLauncher is terminated.");
                Platform.exit();
            }
        }
        try {
            FileUtils.ensureWritableDir(gameDirectory);
        } catch (IOException e) {
            logger.error("The game directory can not be created or used! '{}'", gameDirectory, e);
            GuiUtils.showErrorMessageDialog(owner, BundleUtils.getLabel("message_error_gameDirectory") + "\n" + gameDirectory);
            throw new LauncherStartFailedException();
        }
        logger.debug("Game directory: {}", gameDirectory);
        return gameDirectory;
    }

    private Path getGameDataDirectory(OperatingSystem os, Path settingsGameDataDirectory) throws LauncherStartFailedException {
        logger.trace("Init GameDataDirectory...");
        Path gameDataDirectory = settingsGameDataDirectory;
        if (gameDataDirectory != null) {
            try {
                FileUtils.ensureWritableDir(gameDataDirectory);
            } catch (IOException e) {
                logger.warn("The game data directory can not be created or used! '{}'", gameDataDirectory, e);
                GuiUtils.showWarningMessageDialog(owner, BundleUtils.getLabel("message_error_gameDataDirectory") + "\n"
                        + gameDataDirectory);

                // Set gameDataDirectory to 'null' -> user has to choose new game data directory
                gameDataDirectory = null;
            }
        }
        if (gameDataDirectory == null) {
            logger.trace("Choose data directory for the game...");
            updateMessage(BundleUtils.getLabel("splash_chooseGameDataDirectory"));
            gameDataDirectory = GuiUtils.chooseDirectoryDialog(owner, LauncherDirectoryUtils.getGameDataDirectory(os),
                    BundleUtils.getLabel("message_dialog_title_chooseGameDataDirectory"));
            if (Files.notExists(gameDataDirectory)) {
                logger.info("The new game data directory is not approved. The TerasologyLauncher is terminated.");
                Platform.exit();
            }
        }
        try {
            FileUtils.ensureWritableDir(gameDataDirectory);
        } catch (IOException e) {
            logger.error("The game data directory can not be created or used! '{}'", gameDataDirectory, e);
            GuiUtils.showErrorMessageDialog(owner, BundleUtils.getLabel("message_error_gameDataDirectory") + "\n" + gameDataDirectory);
            throw new LauncherStartFailedException();
        }
        logger.debug("Game data directory: {}", gameDataDirectory);
        return gameDataDirectory;
    }

    private void storeLauncherSettingsAfterInit(BaseLauncherSettings launcherSettings) throws LauncherStartFailedException {
        logger.trace("Store LauncherSettings...");
        updateMessage(BundleUtils.getLabel("splash_storeLauncherSettings"));
        try {
            launcherSettings.store();
        } catch (IOException e) {
            logger.error("The launcher settings can not be stored! '{}'", launcherSettings.getLauncherSettingsFilePath(), e);
            GuiUtils.showErrorMessageDialog(owner, BundleUtils.getLabel("message_error_storeSettings"));
            throw new LauncherStartFailedException();
        }
        logger.debug("Launcher Settings stored: {}", launcherSettings);
    }
}
