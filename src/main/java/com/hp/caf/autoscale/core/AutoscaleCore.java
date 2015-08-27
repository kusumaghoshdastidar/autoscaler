package com.hp.caf.autoscale.core;


import com.hp.caf.api.ConfigurationSource;
import com.hp.caf.api.Election;
import com.hp.caf.api.ElectionCallback;
import com.hp.caf.api.ElectionException;
import com.hp.caf.api.ElectionFactory;
import com.hp.caf.api.ServicePath;
import com.hp.caf.api.autoscale.ScalerException;
import com.hp.caf.api.autoscale.ServiceScaler;
import com.hp.caf.api.autoscale.ServiceSource;
import com.hp.caf.api.autoscale.WorkloadAnalyserFactory;
import com.hp.caf.api.autoscale.WorkloadAnalyserFactoryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


/**
 * The AutoscaleCore is responsible for three major things: finding services
 * it is responsible for scaling (from a ServiceSource), scheduling workload analysis
 * of these services (using a ScheduledExecutorService and available WorkloadAnalyser
 * classes), and actually triggering the scaling (using a ServiceScaler).
 *
 * Each service it is monitoring will have an associated ScalerThread which periodically
 * performs analysis of the service's workload and potentially triggers the scaling.
 *
 * The AutoscaleCore will also periodically retrieve new and updated services to
 * monitor the scaling of. If an existing application had its ScalingConfiguration changed,
 * the monitoring thread will be cancelled and rescheduled. Removed services will be cancelled.
 *
 * All instances of the AutoscaleCore will monitor all services with the same
 * "group" (which dictates the services it is responsible for scaling). However,
 * only the elected master instance will actually trigger the scaling. This way, if the master
 * fails over to another instance, it already has historical data to perform scaling with.
 */
public class AutoscaleCore
{
    public static final String AUTOSCALE_SERVICE_NAME = "autoscale";
    private ScheduledFuture<?> refreshFuture;
    private final ScalerDecorator scaler;
    private final ScheduledExecutorService scheduler;
    private final ServiceSource source;
    private final Election election;
    private final AutoscaleScheduler autoscaleScheduler;
    private final Map<String, WorkloadAnalyserFactory> analyserFactoryMap = new HashMap<>();
    private static final Logger LOG = LoggerFactory.getLogger(AutoscaleCore.class);


    public AutoscaleCore(final ConfigurationSource configSource, final ServiceSource serviceSource, final ServiceScaler serviceScaler,
                         final Collection<WorkloadAnalyserFactoryProvider> workloadProviders, final ElectionFactory electionFactory,
                         final ScheduledExecutorService scheduler, final ServicePath servicePath)
        throws ScalerException
    {
        if ( workloadProviders.isEmpty() ) {
            throw new ScalerException("No instances of WorkloadAnalyserFactory found");
        }
        for ( WorkloadAnalyserFactoryProvider provider : workloadProviders ) {
            LOG.debug("Registering workload analyser: {}", provider.getWorkloadAnalyserName());
            analyserFactoryMap.put(provider.getWorkloadAnalyserName(), provider.getWorkloadAnalyserFactory(configSource));
        }
        this.scaler = new ScalerDecorator(serviceScaler, false);
        this.scheduler = scheduler;
        this.source = serviceSource;
        ServiceValidator validator = new ServiceValidator(Collections.unmodifiableCollection(analyserFactoryMap.keySet()));
        this.autoscaleScheduler = new AutoscaleScheduler(Collections.unmodifiableMap(analyserFactoryMap), scaler, scheduler, validator);
        this.election = electionFactory.getElection(servicePath.getGroup() + "-" + AUTOSCALE_SERVICE_NAME, new AutoscaleElectionCallback());
    }


    /**
     * Enter the election, update the current list of services, and schedule a periodic refresh of available services to scale.
     * If we can't get the current list of services at startup, AutoscaleCore will continue anyway, assuming that the list will become
     * available over time and hand the responsibility for this off to a periodic scheduled thread. However, if we cannot even enter
     * the election, this is considered a failure.
     * @param refreshPeriod the period in seconds to wait between refreshing the list of services to scale
     * @throws ElectionException if this instance cannot enter the election
     */
    public void start(final int refreshPeriod)
        throws ElectionException
    {
        election.enter();
        try {
            autoscaleScheduler.updateServices(source.getServices());
        } catch (ScalerException e) {
            LOG.warn("Couldn't update services on start, continuing anyway", e);
        }
        refreshFuture = scheduler.scheduleWithFixedDelay(new ServiceRefreshThread(), refreshPeriod, refreshPeriod, TimeUnit.SECONDS);
    }


    /**
     * Remove this instance from the election, cancel the refresh thread, and shutdown the scheduler.
     */
    public void shutdown()
    {
        LOG.info("Shutting down");
        election.resign();
        if ( refreshFuture != null ) {
            refreshFuture.cancel(true);
        }
        autoscaleScheduler.shutdown();
    }


    /**
     * @return the map of currently available WorkloadAnalyserFactory classes
     */
    public Map<String, WorkloadAnalyserFactory> getAnalyserFactoryMap()
    {
        return Collections.unmodifiableMap(this.analyserFactoryMap);
    }


    /**
     * Set or unset master status based upon a callback from an election.
     */
    private class AutoscaleElectionCallback implements ElectionCallback
    {
        @Override
        public void elected()
        {
            LOG.info("This service has now been elected master");
            scaler.setMaster(true);
        }


        @Override
        public void rejected()
        {
            LOG.info("This service is no longer the master");
            scaler.setMaster(false);
        }
    }


    /**
     * Just something to reload the services from the source occasionally.
     */
    private class ServiceRefreshThread implements Runnable
    {
        @Override
        public void run()
        {
            try {
                autoscaleScheduler.updateServices(source.getServices());
            } catch (ScalerException e) {
                LOG.warn("Failed to retrieve services this run", e);
            }
        }
    }
}
