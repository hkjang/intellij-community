// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.idea;

import com.intellij.diagnostic.IdeErrorsDialog;
import com.intellij.diagnostic.LogMessage;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.application.impl.ApplicationImpl;
import com.intellij.openapi.application.impl.ApplicationInfoImpl;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.*;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.ExceptionUtil;
import org.apache.log4j.DefaultThrowableRenderer;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggerRepository;
import org.apache.log4j.spi.ThrowableRenderer;
import org.apache.log4j.spi.ThrowableRendererSupport;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Mike
 */
public class IdeaLogger extends Log4jBasedLogger {
  @SuppressWarnings("StaticNonFinalField") public static String ourLastActionId = "";
  @SuppressWarnings("StaticNonFinalField") public static Exception ourErrorsOccurred;  // when not null, holds the first of errors that occurred

  private static final ApplicationInfoProvider ourApplicationInfoProvider;
  private static final ThrowableRenderer ourThrowableRenderer;

  static {
    ourApplicationInfoProvider = () -> {
      ApplicationInfoEx info = ApplicationInfoImpl.getShadowInstance();
      return info.getFullApplicationName() + "  " + "Build #" + info.getBuild().asString();
    };

    ourThrowableRenderer = t -> {
      String[] lines = DefaultThrowableRenderer.render(t);
      int maxStackSize = 1024;
      int maxExtraSize = 256;
      if (lines.length > maxStackSize + maxExtraSize) {
        String[] res = new String[maxStackSize + maxExtraSize + 1];
        System.arraycopy(lines, 0, res, 0, maxStackSize);
        res[maxStackSize] = "\t...";
        System.arraycopy(lines, lines.length - maxExtraSize, res, maxStackSize + 1, maxExtraSize);
        return res;
      }
      return lines;
    };
  }

  /**
   * @deprecated returns {@code null} always
   */
  @ApiStatus.ScheduledForRemoval(inVersion = "2019.3")
  @Deprecated
  @Nullable
  public static String getOurCompilationTimestamp() {
    return null;
  }

  @NotNull
  public static ThrowableRenderer getThrowableRenderer() {
    return ourThrowableRenderer;
  }

  IdeaLogger(@NotNull Logger logger) {
    super(logger);
    LoggerRepository repository = myLogger.getLoggerRepository();
    if (repository instanceof ThrowableRendererSupport) {
      ((ThrowableRendererSupport)repository).setThrowableRenderer(ourThrowableRenderer);
    }
  }

  @Override
  public void error(Object message) {
    if (message instanceof IdeaLoggingEvent) {
      myLogger.error(message);
    }
    else {
      super.error(message);
    }
  }

  @Override
  public void error(String message, @Nullable Throwable t, @NotNull Attachment... attachments) {
    myLogger.error(LogMessage.createEvent(t != null ? t : new Throwable(), message, attachments));
  }

  @Override
  public void warn(String message, @Nullable Throwable t) {
    super.warn(message, checkException(t));
  }

  @Override
  public void error(String message, @Nullable Throwable t, @NotNull String... details) {
    if (t instanceof ControlFlowException) {
      myLogger.error(message, checkException(t));
      ExceptionUtil.rethrow(t);
    }

    String detailString = StringUtil.join(details, "\n");

    if (!detailString.isEmpty()) {
      detailString = "\nDetails: " + detailString;
    }

    if (ourErrorsOccurred == null) {
      String mess = "Logger errors occurred. See IDEA logs for details. " +
                    (StringUtil.isEmpty(message) ? "" : "Error message is '" + message + "'");
      //noinspection AssignmentToStaticFieldFromInstanceMethod
      ourErrorsOccurred = new Exception(mess + detailString, t);
    }
    myLogger.error(message + detailString, t);
    logErrorHeader(t);
  }

  private void logErrorHeader(@Nullable Throwable t) {
    myLogger.error(ourApplicationInfoProvider.getInfo());

    myLogger.error("JDK: " + System.getProperties().getProperty("java.version", "unknown")+
                   "; VM: " + System.getProperties().getProperty("java.vm.name", "unknown") +
                   "; Vendor: " + System.getProperties().getProperty("java.vendor", "unknown"));
    myLogger.error("OS: " + System.getProperties().getProperty("os.name", "unknown"));

    IdeaPluginDescriptor plugin = t == null ? null : findPluginIfInitialized(t);
    if (plugin != null && (!plugin.isBundled() || plugin.allowBundledUpdate())) {
      myLogger.error("Plugin to blame: " + plugin.getName() + " version: " + plugin.getVersion());
    }

    ApplicationImpl application = (ApplicationImpl)ApplicationManager.getApplication();
    if (application != null && application.isComponentsCreated() && !application.isDisposed()) {
      String lastPreformedActionId = ourLastActionId;
      if (lastPreformedActionId != null) {
        myLogger.error("Last Action: " + lastPreformedActionId);
      }

      CommandProcessor commandProcessor = CommandProcessor.getInstance();
      if (commandProcessor != null) {
        String currentCommandName = commandProcessor.getCurrentCommandName();
        if (currentCommandName != null) {
          myLogger.error("Current Command: " + currentCommandName);
        }
      }
    }
  }

  // return plugin mentioned in this exception (only if all plugins are initialized, to avoid stack overflow when exception is thrown during plugin init)
  private static IdeaPluginDescriptor findPluginIfInitialized(@NotNull Throwable t) {
    return PluginManagerCore.arePluginsInitialized() ? PluginManager.getPlugin(IdeErrorsDialog.findPluginId(t)) : null;
  }
}