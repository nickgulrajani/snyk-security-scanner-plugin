package io.snyk.tools;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.EnvironmentSpecific;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.NodeSpecific;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolProperty;
import io.snyk.Messages;
import org.kohsuke.stapler.DataBoundConstructor;

public class SnykInstallation extends ToolInstallation implements EnvironmentSpecific<SnykInstallation>, NodeSpecific<SnykInstallation> {

  private transient Platform platform;

  @DataBoundConstructor
  public SnykInstallation(@Nonnull String name, @Nonnull String home, List<? extends ToolProperty<?>> properties) {
    this(name, home, properties, null);
  }

  protected SnykInstallation(@Nonnull String name, @Nonnull String home, List<? extends ToolProperty<?>> properties, Platform platform) {
    super(name, home, properties);
    this.platform = platform;
  }

  @Override
  public SnykInstallation forEnvironment(EnvVars envVars) {
    return null;
  }

  @Override
  public SnykInstallation forNode(@Nonnull Node node, TaskListener taskListener) {
    return null;
  }

  private Platform getPlatform() throws ToolDetectionException {
    Platform currentPlatform = platform;

    if (currentPlatform == null) {
      Computer computer = Computer.currentComputer();
      if (computer != null) {
        Node node = computer.getNode();
        if (node == null) {
          throw new ToolDetectionException(Messages.Tools_nodeOffline());
        }
        currentPlatform = Platform.of(node);
      } else {
        // pipeline or master-to-slave case
        currentPlatform = Platform.current();
      }
      platform = currentPlatform;
    }

    return currentPlatform;
  }

  @Extension
  //TODO: symbol
  public static class DescriptorImpl extends ToolDescriptor<SnykInstallation> {

    @Nonnull
    @Override
    public String getDisplayName() {
      return "Snyk";
    }

    public List<? extends ToolInstaller> getDefaultInstallers() {
      return Collections.singletonList(new SnykInstaller(null));
    }

    @Override
    public SnykInstallation[] getInstallations() {
      //TODO: handle installation on master/node
      return super.getInstallations();
    }

    @Override
    public void setInstallations(SnykInstallation... installations) {
      //TODO: handle installation on master/node
      super.setInstallations(installations);
    }
  }
}
