// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.serviceContainer;

import com.intellij.diagnostic.LoadingPhase;
import com.intellij.diagnostic.ParallelActivity;
import com.intellij.diagnostic.PluginException;
import com.intellij.diagnostic.StartUpMeasurer;
import com.intellij.ide.plugins.ContainerDescriptor;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.IdeaPluginDescriptorImpl;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.ServiceDescriptor;
import com.intellij.openapi.components.impl.ComponentManagerImpl;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.extensions.impl.ExtensionComponentAdapter;
import com.intellij.openapi.progress.ProgressIndicatorProvider;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.PlatformUtils;
import com.intellij.util.io.storage.HeavyProcessLatch;
import com.intellij.util.pico.AssignableToComponentAdapter;
import com.intellij.util.pico.CachingConstructorInjectionComponentAdapter;
import com.intellij.util.pico.DefaultPicoContainer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.picocontainer.*;
import org.picocontainer.defaults.InstanceComponentAdapter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

public final class ServiceManagerImpl implements Disposable {
  private static final Logger LOG = Logger.getInstance(ServiceManagerImpl.class);

  @NotNull
  static ComponentAdapter createServiceAdapter(@NotNull ServiceDescriptor descriptor,
                                               @NotNull IdeaPluginDescriptor pluginDescriptor,
                                               @NotNull PlatformComponentManagerImpl componentManager) {
    return new MyComponentAdapter(descriptor, pluginDescriptor, componentManager);
  }

  @ApiStatus.Internal
  public static void processAllDescriptors(@NotNull ComponentManager componentManager, @NotNull Consumer<? super ServiceDescriptor> consumer) {
    for (IdeaPluginDescriptor plugin : PluginManagerCore.getLoadedPlugins()) {
      IdeaPluginDescriptorImpl pluginDescriptor = (IdeaPluginDescriptorImpl)plugin;
      ContainerDescriptor containerDescriptor;
      if (componentManager instanceof Application) {
        containerDescriptor = pluginDescriptor.getApp();
      }
      else if (componentManager instanceof Project) {
        containerDescriptor = pluginDescriptor.getProject();
      }
      else {
        containerDescriptor = pluginDescriptor.getModule();
      }

      containerDescriptor.getServices().forEach(consumer);
    }
  }

  @ApiStatus.Internal
  public static void processProjectDescriptors(@NotNull BiConsumer<? super ServiceDescriptor, ? super PluginDescriptor> consumer) {
    for (IdeaPluginDescriptor plugin : PluginManagerCore.getLoadedPlugins()) {
      for (ServiceDescriptor serviceDescriptor : ((IdeaPluginDescriptorImpl)plugin).getProject().getServices()) {
        consumer.accept(serviceDescriptor, plugin);
      }
    }
  }

  public static void processAllImplementationClasses(@NotNull ComponentManager componentManager,
                                                     @NotNull BiPredicate<? super Class<?>, ? super PluginDescriptor> processor) {
    @SuppressWarnings("unchecked")
    Collection<ComponentAdapter> adapters = componentManager.getPicoContainer().getComponentAdapters();
    if (adapters.isEmpty()) {
      return;
    }

    for (ComponentAdapter o : adapters) {
      Class<?> aClass;
      if (o instanceof MyComponentAdapter) {
        MyComponentAdapter adapter = (MyComponentAdapter)o;
        PluginDescriptor pluginDescriptor = adapter.myPluginDescriptor;
        try {
          ComponentAdapter delegate = adapter.myDelegate;
          // avoid delegation creation & class initialization
          if (delegate == null) {
            ClassLoader classLoader = pluginDescriptor.getPluginClassLoader();
            aClass = Class.forName(adapter.myDescriptor.getImplementation(), false, classLoader);
          }
          else {
            aClass = delegate.getComponentImplementation();
          }
        }
        catch (Throwable e) {
          if (PlatformUtils.isIdeaUltimate()) {
            LOG.error(e);
          }
          else {
            // well, component registered, but required jar is not added to classpath (community edition or junior IDE)
            LOG.warn(e);
          }
          continue;
        }

        if (!processor.test(aClass, pluginDescriptor)) {
          break;
        }
      }
      else if (!(o instanceof ExtensionComponentAdapter)) {
        PluginId pluginId = ComponentManagerImpl.getConfig(o);
        // allow InstanceComponentAdapter without pluginId to test
        if (pluginId != null || o instanceof InstanceComponentAdapter) {
          try {
            aClass = o.getComponentImplementation();
          }
          catch (Throwable e) {
            LOG.error(e);
            continue;
          }

          if (!processor.test(aClass, pluginId == null ? null : PluginManagerCore.getPlugin(pluginId))) {
            break;
          }
        }
      }
    }
  }

  @Override
  public void dispose() {
  }

  public static List<Object> unloadServices(ContainerDescriptor containerDescriptor, ComponentManager componentManager) {
    List<Object> unloadedInstances = new ArrayList<>();
    MutablePicoContainer picoContainer = (MutablePicoContainer)componentManager.getPicoContainer();
    for (ServiceDescriptor service : containerDescriptor.getServices()) {
      ComponentAdapter adapter = picoContainer.unregisterComponent(service.getInterface());
      if (adapter instanceof DefaultPicoContainer.LazyComponentAdapter) {
        DefaultPicoContainer.LazyComponentAdapter lazyAdapter = (DefaultPicoContainer.LazyComponentAdapter)adapter;
        if (lazyAdapter.isComponentInstantiated()) {
          Object instance = adapter.getComponentInstance(picoContainer);
          if (instance instanceof Disposable) {
            Disposer.dispose((Disposable)instance);
          }
          unloadedInstances.add(instance);
        }
      }
    }
    return unloadedInstances;
  }

  private static class MyComponentAdapter implements AssignableToComponentAdapter, DefaultPicoContainer.LazyComponentAdapter {
    private ComponentAdapter myDelegate;
    private final PluginDescriptor myPluginDescriptor;
    private final ServiceDescriptor myDescriptor;
    private final PlatformComponentManagerImpl myComponentManager;
    private volatile Object myInitializedComponentInstance;

    MyComponentAdapter(@NotNull ServiceDescriptor descriptor, @NotNull PluginDescriptor pluginDescriptor, @NotNull PlatformComponentManagerImpl componentManager) {
      myDescriptor = descriptor;
      myPluginDescriptor = pluginDescriptor;
      myComponentManager = componentManager;
    }

    @Override
    public String getComponentKey() {
      return myDescriptor.getInterface();
    }

    @Override
    public Class<?> getComponentImplementation() {
      return getDelegate().getComponentImplementation();
    }

    @Override
    public boolean isComponentInstantiated() {
      return myInitializedComponentInstance != null;
    }

    @Override
    public Object getComponentInstance(@NotNull PicoContainer container) throws PicoInitializationException, PicoIntrospectionException {
      Object instance = myInitializedComponentInstance;
      if (instance != null) {
        return instance;
      }

      LoadingPhase.COMPONENT_REGISTERED.assertAtLeast();

      //noinspection SynchronizeOnThis
      synchronized (this) {
        instance = myInitializedComponentInstance;
        if (instance != null) {
          // DCL is fine, field is volatile
          return instance;
        }

        String implementation = myDescriptor.getImplementation();
        if (LOG.isDebugEnabled() &&
            ApplicationManager.getApplication().isWriteAccessAllowed() &&
            !ApplicationManager.getApplication().isUnitTestMode() &&
            PersistentStateComponent.class.isAssignableFrom(getDelegate().getComponentImplementation())) {
          LOG.warn(new Throwable("Getting service from write-action leads to possible deadlock. Service implementation " +
                                 implementation));
        }

        // heavy to prevent storages from flushing and blocking FS
        try (AccessToken ignore = HeavyProcessLatch.INSTANCE.processStarted("Creating service '" + implementation + "'")) {
          if (ProgressIndicatorProvider.getGlobalProgressIndicator() == null) {
            myInitializedComponentInstance = createAndInitialize(container);
          }
          else {
            //noinspection CodeBlock2Expr
            ProgressManager.getInstance().executeNonCancelableSection(() -> {
              myInitializedComponentInstance = createAndInitialize(container);
            });
          }
          return myInitializedComponentInstance;
        }
      }
    }

    @NotNull
    private Object createAndInitialize(@NotNull PicoContainer container) {
      long startTime = StartUpMeasurer.getCurrentTime();
      Object instance = getDelegate().getComponentInstance(container);
      if (instance instanceof Disposable) {
        Disposer.register(myComponentManager, (Disposable)instance);
      }

      myComponentManager.initializeComponent(instance, myDescriptor);
      ParallelActivity.SERVICE.record(startTime, instance.getClass(), DefaultPicoContainer.getActivityLevel(container), myPluginDescriptor.getPluginId().getIdString());
      return instance;
    }

    @NotNull
    private synchronized ComponentAdapter getDelegate() {
      if (myDelegate == null) {
        Class<?> implClass;
        try {
          ClassLoader classLoader = myPluginDescriptor.getPluginClassLoader();
          implClass = Class.forName(myDescriptor.getImplementation(), true, classLoader);
        }
        catch (ClassNotFoundException e) {
          throw new PluginException("Failed to load class: " + myDescriptor, e, myPluginDescriptor.getPluginId());
        }

        myDelegate = new CachingConstructorInjectionComponentAdapter(getComponentKey(), implClass, null, true);
      }
      return myDelegate;
    }

    @Override
    public void verify(PicoContainer container) {
    }

    @Override
    public void accept(final PicoVisitor visitor) {
      visitor.visitComponentAdapter(this);
    }

    @Override
    public String getAssignableToClassName() {
      return myDescriptor.getInterface();
    }

    @Override
    public String toString() {
      return "ServiceComponentAdapter(descriptor=" + myDescriptor + ", pluginDescriptor=" + myPluginDescriptor + ")";
    }
  }
}