package com.hp.caf.autoscale.core;


import com.hp.caf.api.BootstrapConfiguration;
import com.hp.caf.api.Cipher;
import com.hp.caf.api.CipherException;
import com.hp.caf.api.CipherProvider;
import com.hp.caf.api.Codec;
import com.hp.caf.api.ConfigurationException;
import com.hp.caf.api.ConfigurationSource;
import com.hp.caf.api.ConfigurationSourceProvider;
import com.hp.caf.api.ElectionException;
import com.hp.caf.api.ElectionFactory;
import com.hp.caf.api.ElectionFactoryProvider;
import com.hp.caf.api.ServicePath;
import com.hp.caf.api.autoscale.ScalerException;
import com.hp.caf.api.autoscale.ServiceScaler;
import com.hp.caf.api.autoscale.ServiceScalerProvider;
import com.hp.caf.api.autoscale.ServiceSource;
import com.hp.caf.api.autoscale.ServiceSourceProvider;
import com.hp.caf.api.autoscale.WorkloadAnalyserFactory;
import com.hp.caf.api.autoscale.WorkloadAnalyserFactoryProvider;
import com.hp.caf.cipher.NullCipherProvider;
import com.hp.caf.config.system.SystemBootstrapConfiguration;
import com.hp.caf.election.NullElectionFactoryProvider;
import com.hp.caf.util.ComponentLoader;
import com.hp.caf.util.ComponentLoaderException;
import io.dropwizard.Application;
import io.dropwizard.setup.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;


/**
 * Wrapper around AutoscaleCore to expose functionality as a Dropwizard application.
 */
public class AutoscaleApplication extends Application<AutoscaleConfiguration>
{
    private static final Logger LOG = LoggerFactory.getLogger(AutoscaleApplication.class);


    /**
     * Standard entry point for an AutoscaleApplication.
     * @param args comamnd line arguments
     * @throws Exception if startup fails
     */
    public static void main(final String[] args)
            throws Exception
    {
        new AutoscaleApplication().run(args);
    }


    AutoscaleApplication() { }


    /**
     * Called upon startup. Determine required components from the classpath.
     * AutoscaleApplication requires the following advertised services on the classpath: a ConfigurationSourceProvider,
     * a ServiceSourceProvider, a ServiceScalerProvider, a Codec, an ElectionFactoryProvider, and at least one instance of a
     * WorkloadAnalyserFactoryProvider (but there can be more). This will create an instance of AutoscaleCore and set up health checks.
     * @param autoscaleConfiguration AutoscaleApplication configuration
     * @param environment to access health checks and metrics
     */
    @Override
    public void run(final AutoscaleConfiguration autoscaleConfiguration, final Environment environment)
        throws ScalerException, ConfigurationException, ComponentLoaderException, CipherException, ElectionException
    {
        LOG.info("Starting up");
        BootstrapConfiguration bootstrap = new SystemBootstrapConfiguration();
        ServicePath servicePath = bootstrap.getServicePath();
        Codec codec = ComponentLoader.getService(Codec.class);
        Cipher cipher = ComponentLoader.getService(CipherProvider.class, NullCipherProvider.class).getCipher(bootstrap);
        ConfigurationSource config = ComponentLoader.getService(ConfigurationSourceProvider.class).getConfigurationSource(bootstrap, cipher, servicePath, codec);
        ServiceSource source = ComponentLoader.getService(ServiceSourceProvider.class).getServiceSource(config, servicePath);
        ServiceScaler scaler = ComponentLoader.getService(ServiceScalerProvider.class).getServiceScaler(config);
        ElectionFactory electionFactory = ComponentLoader.getService(ElectionFactoryProvider.class, NullElectionFactoryProvider.class).getElectionManager( config);
        Collection<WorkloadAnalyserFactoryProvider> workloadProviders = ComponentLoader.getServices(WorkloadAnalyserFactoryProvider.class);
        ScheduledExecutorService scheduler = getDefaultScheduledExecutorService(autoscaleConfiguration.getExecutorThreads());
        AutoscaleCore core = new AutoscaleCore(config, source, scaler, workloadProviders, electionFactory, scheduler, servicePath);

        registerHealthChecks(environment, source, scaler, core);
        core.start(autoscaleConfiguration.getSourceRefreshPeriod());
        Runtime.getRuntime().addShutdownHook(new Thread()
        {
            @Override
            public void run()
            {
                core.shutdown();
                config.shutdown();
                scheduler.shutdownNow();
            }
        });
    }


    /**
     * Get a default implementation of a ScheduledExecutorService as used by the autoscaler.
     * @param nThreads the number of threads to make available in the thread pool
     * @return the default instance of a ScheduledExecutorService as used by the autoscale application
     */
    public static ScheduledExecutorService getDefaultScheduledExecutorService(final int nThreads)
    {
        return Executors.newScheduledThreadPool(nThreads);
    }


    private void registerHealthChecks(final Environment environment, final ServiceSource source, final ServiceScaler scaler, final AutoscaleCore core)
    {
        environment.healthChecks().register("source", new ScalerHealthCheck(source));
        environment.healthChecks().register("scaler", new ScalerHealthCheck(scaler));
        for ( Map.Entry<String, WorkloadAnalyserFactory> entry : core.getAnalyserFactoryMap().entrySet() ) {
            environment.healthChecks().register("workload." + entry.getKey(), new ScalerHealthCheck(entry.getValue()));
        }
    }
}
