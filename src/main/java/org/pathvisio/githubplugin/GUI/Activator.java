/*******************************************************************************
 * PathVisio, a tool for data visualization and analysis using biological pathways
 * Copyright 2006-2026 PathVisio
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package org.pathvisio.githubplugin.GUI;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.pathvisio.desktop.plugin.Plugin;

/**
 * OSGi bundle lifecycle entry point for the WikiPathways GitHub Integration Plugin.
 *
 * <p>
 * PathVisio's {@code PluginManager.initPlugins()} discovers plugins exclusively by
 * querying the OSGi service registry for services registered under
 * {@link Plugin}. It does not scan for a {@code Main-Class} or instantiate any
 * class by reflection. This {@code Activator} is therefore the class that makes
 * {@link WikiPathwaysGitHubPlugin} visible to PathVisio at all: without it, a
 * correctly-formed OSGi manifest alone is not sufficient for the plugin to load.
 * </p>
 *
 * <p>
 * The bundle's {@code Bundle-Activator} manifest header must point at this class's
 * fully-qualified name for the OSGi framework (Equinox) to invoke {@link #start(BundleContext)}
 * when the bundle starts and {@link #stop(BundleContext)} when it stops.
 * </p>
 *
 * @author Snehashree Prusty
 * @see WikiPathwaysGitHubPlugin
 * @see Plugin
 */
public class Activator implements BundleActivator
{
    /**
     * Handle to the service registration created in {@link #start(BundleContext)}.
     * Retained so {@link #stop(BundleContext)} can cleanly unregister the same
     * service instance it registered.
     */
    private ServiceRegistration<Plugin> pluginServiceRegistration;

    /**
     * Invoked by the OSGi framework when this bundle is started.
     *
     * <p>
     * Constructs the plugin instance and registers it as a {@link Plugin} OSGi
     * service, following the same pattern used by PathVisio desktop's own
     * {@code Activator}. Once registered, {@code PluginManager.initPlugins()}
     * will find this service reference and PathVisio will call
     * {@link WikiPathwaysGitHubPlugin#init(org.pathvisio.desktop.PvDesktop)} on it.
     * </p>
     *
     * @param context the bundle context provided by the OSGi framework
     */
    @Override
    public void start(BundleContext context)
    {
        WikiPathwaysGitHubPlugin pluginInstance = new WikiPathwaysGitHubPlugin();
        pluginServiceRegistration =
            context.registerService(Plugin.class, pluginInstance, null);
    }

    /**
     * Invoked by the OSGi framework when this bundle is stopped.
     *
     * <p>
     * Unregisters the {@link Plugin} service registered in {@link #start(BundleContext)},
     * so no stale service reference remains in the registry after the bundle stops.
     * </p>
     *
     * @param context the bundle context provided by the OSGi framework
     */
    @Override
    public void stop(BundleContext context)
    {
        if (pluginServiceRegistration != null)
        {
            pluginServiceRegistration.unregister();
            pluginServiceRegistration = null;
        }
        else
        {
            // Nothing to unregister — start() may not have completed successfully.
        }
    }
}