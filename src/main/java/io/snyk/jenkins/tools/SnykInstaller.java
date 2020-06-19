package io.snyk.jenkins.tools;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import hudson.Extension;
import hudson.FilePath;
import hudson.Functions;
import hudson.Launcher;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolInstallerDescriptor;
import hudson.util.ArgumentListBuilder;
import io.snyk.jenkins.tools.internal.DownloadService;
import jenkins.security.MasterToSlaveCallable;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static hudson.Util.fixEmptyAndTrim;
import static java.lang.String.format;
import static java.lang.String.valueOf;
import static java.nio.charset.StandardCharsets.UTF_8;

public class SnykInstaller extends ToolInstaller {

  private static final Logger LOG = LoggerFactory.getLogger(SnykInstaller.class.getName());
  private static final String INSTALLED_FROM = ".installedFrom";
  private static final String TIMESTAMP_FILE = ".timestamp";

  private final String version;
  private final Long updatePolicyIntervalHours;

  @DataBoundConstructor
  public SnykInstaller(String label, String version, Long updatePolicyIntervalHours) {
    super(label);
    this.version = version;
    this.updatePolicyIntervalHours = updatePolicyIntervalHours;
  }

  @Override
  public FilePath performInstallation(ToolInstallation tool, Node node, TaskListener log) throws IOException, InterruptedException {
    FilePath expected = preferredLocation(tool, node);

    if (isUpToDate(expected)) {
      log.getLogger().println("Snyk installation is UP-TO-DATE");
      return expected;
    }

    log.getLogger().println("Installing Snyk Security tool (version '" + fixEmptyAndTrim(version) + "')");
    if (isNpmAvailable(node, log)) {
      return installSnykAsNpmPackage(expected, node, log);
    } else {
      return installSnykAsSingleBinary(expected, node, log);
    }
  }

  private boolean isUpToDate(FilePath expectedLocation) throws IOException, InterruptedException {
    FilePath marker = expectedLocation.child(TIMESTAMP_FILE);
    if (!marker.exists()) {
      return false;
    }

    String content = StringUtils.chomp(marker.readToString());
    long timestampFromFile;
    try {
      timestampFromFile = Long.parseLong(content);
    } catch (NumberFormatException ex) {
      // corrupt of modified .timestamp file => force new installation
      LOG.error(".timestamp file is corrupt and cannot be read and will be reset to 0.");
      timestampFromFile = 0;
    }
    long timestampNow = Instant.now().toEpochMilli();

    long timestampDifference = timestampNow - timestampFromFile;
    if (timestampDifference <= 0) {
      return true;
    }
    long updateInterval = TimeUnit.HOURS.toMillis(updatePolicyIntervalHours);
    return timestampDifference < updateInterval;
  }

  private boolean isNpmAvailable(Node node, TaskListener log) {
    Launcher launcher = node.createLauncher(log);
    Launcher.ProcStarter ps = launcher.new ProcStarter();
    ps.quiet(true).cmds("npm", "--version");

    try {
      int exitCode = launcher.launch(ps).join();
      return exitCode == 0;
    } catch (Exception ex) {
      LOG.info("NPM is not available on the node: '{}'", node.getDisplayName());
      LOG.debug("'npm --version' command failed", ex);
      return false;
    }
  }

  private FilePath installSnykAsNpmPackage(FilePath expected, Node node, TaskListener log) throws ToolDetectionException {
    LOG.info("Install Snyk version '{}' as NPM package on node '{}'", version, node.getDisplayName());

    ArgumentListBuilder args = new ArgumentListBuilder();
    args.add("npm", "install", "--prefix", expected.getRemote(), "snyk@" + fixEmptyAndTrim(version), "snyk-to-html");
    Launcher launcher = node.createLauncher(log);
    Launcher.ProcStarter ps = launcher.new ProcStarter();
    ps.quiet(true).cmds(args);

    try {
      int exitCode = launcher.launch(ps).join();
      if (exitCode != 0) {
        log.getLogger().println("Snyk installation was not successful. Exit code: " + exitCode);
        return expected;
      }
      expected.child(TIMESTAMP_FILE).write(valueOf(Instant.now().toEpochMilli()), UTF_8.name());
    } catch (Exception ex) {
      log.getLogger().println("Snyk Security tool could not installed: " + ex.getMessage());
      LOG.error("Could not install Snyk as NPM package", ex);
      throw new ToolDetectionException("Could not install Snyk CLI with npm", ex);
    }
    return expected;
  }

  private FilePath installSnykAsSingleBinary(FilePath expected, Node node, TaskListener log) throws IOException, InterruptedException, ToolDetectionException {
    LOG.info("Install Snyk version '{}' as single binary on node '{}'", version, node.getDisplayName());

    final VirtualChannel nodeChannel = node.getChannel();
    if (nodeChannel == null) {
      throw new IOException(format("Node '%s' is offline", node.getDisplayName()));
    }

    Platform platform = nodeChannel.call(new GetPlatform(node.getDisplayName()));

    try {
      URL snykDownloadUrl = DownloadService.getDownloadUrlForSnyk(version, platform);
      URL snykToHtmlDownloadUrl = DownloadService.getDownloadUrlForSnykToHtml(platform);
      expected.mkdirs();
      nodeChannel.call(new Downloader(snykDownloadUrl, expected.child(platform.snykWrapperFileName)));
      nodeChannel.call(new Downloader(snykToHtmlDownloadUrl, expected.child(platform.snykToHtmlWrapperFileName)));
      expected.child(INSTALLED_FROM).write(snykDownloadUrl.toString(), UTF_8.name());
      expected.child(TIMESTAMP_FILE).write(valueOf(Instant.now().toEpochMilli()), UTF_8.name());
    } catch (Exception ex) {
      log.getLogger().println("Snyk Security tool could not installed: " + ex.getMessage());
      LOG.error("Could not install Snyk as single binary", ex);
      throw new ToolDetectionException("Could not install Snyk CLI from binary", ex);
    }

    return expected;
  }

  @SuppressWarnings("unused")
  public String getVersion() {
    return version;
  }

  @SuppressWarnings("unused")
  public Long getUpdatePolicyIntervalHours() {
    return updatePolicyIntervalHours;
  }

  @Extension
  public static final class SnykInstallerDescriptor extends ToolInstallerDescriptor<SnykInstaller> {

    @Override
    public String getDisplayName() {
      return "Install from snyk.io";
    }

    @Override
    public boolean isApplicable(Class<? extends ToolInstallation> toolType) {
      return toolType == SnykInstallation.class;
    }
  }

  private static class GetPlatform extends MasterToSlaveCallable<Platform, IOException> {
    private static final long serialVersionUID = 1L;

    private final String nodeDisplayName;

    GetPlatform(String nodeDisplayName) {
      this.nodeDisplayName = nodeDisplayName;
    }

    @Override
    public Platform call() throws IOException {
      try {
        return Platform.current();
      } catch (ToolDetectionException ex) {
        throw new IOException(format("Could not determine platform on node %s", nodeDisplayName));
      }
    }
  }

  private static class Downloader extends MasterToSlaveCallable<Void, IOException> {
    private static final long serialVersionUID = 1L;

    private final URL downloadUrl;
    private final FilePath output;

    Downloader(URL downloadUrl, FilePath output) {
      this.downloadUrl = downloadUrl;
      this.output = output;
    }

    @Override
    public Void call() throws IOException {
      final File downloadedFile = new File(output.getRemote());
      FileUtils.copyURLToFile(downloadUrl, downloadedFile, 10000, 10000);
      // set execute permission
      if (!Functions.isWindows() && downloadedFile.isFile()) {
        boolean result = downloadedFile.setExecutable(true, false);
        if (!result) {
          throw new IOException(format("Could not set executable flag for the file: %s", downloadedFile.getAbsolutePath()));
        }
      }
      return null;
    }
  }
}
