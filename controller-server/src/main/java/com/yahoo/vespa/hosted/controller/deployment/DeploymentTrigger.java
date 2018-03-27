// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.deployment;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.SystemName;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.ApplicationController;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.LockedApplication;
import com.yahoo.vespa.hosted.controller.application.ApplicationList;
import com.yahoo.vespa.hosted.controller.application.ApplicationVersion;
import com.yahoo.vespa.hosted.controller.application.Change;
import com.yahoo.vespa.hosted.controller.application.Deployment;
import com.yahoo.vespa.hosted.controller.application.DeploymentJobs.JobError;
import com.yahoo.vespa.hosted.controller.application.DeploymentJobs.JobReport;
import com.yahoo.vespa.hosted.controller.application.DeploymentJobs.JobType;
import com.yahoo.vespa.hosted.controller.application.JobList;
import com.yahoo.vespa.hosted.controller.application.JobStatus;
import com.yahoo.vespa.hosted.controller.persistence.CuratorDb;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Responsible for scheduling deployment jobs in a build system and keeping
 * {@link Application#change()} in sync with what is scheduled.
 *
 * This class is multi-thread safe.
 *
 * @author bratseth
 * @author mpolden
 * @author jvenstad
 */
public class DeploymentTrigger {

    /** The max duration a job may run before we consider it dead/hanging */
    private final Duration jobTimeout;

    private final static Logger log = Logger.getLogger(DeploymentTrigger.class.getName());

    private final Controller controller;
    private final Clock clock;
    private final DeploymentQueue deploymentQueue;
    private final DeploymentOrder order;

    public DeploymentTrigger(Controller controller, CuratorDb curator, Clock clock) {
        Objects.requireNonNull(controller,"controller cannot be null");
        Objects.requireNonNull(curator,"curator cannot be null");
        Objects.requireNonNull(clock,"clock cannot be null");
        this.controller = controller;
        this.clock = clock;
        this.deploymentQueue = new DeploymentQueue(controller, curator);
        this.order = new DeploymentOrder(controller);
        this.jobTimeout = controller.system().equals(SystemName.main) ? Duration.ofHours(12) : Duration.ofHours(1);
    }

    /** Returns the time in the past before which jobs are at this moment considered unresponsive */
    public Instant jobTimeoutLimit() { return clock.instant().minus(jobTimeout); }

    public DeploymentQueue deploymentQueue() { return deploymentQueue; }

    public DeploymentOrder deploymentOrder() { return order; }

    //--- Start of methods which triggers deployment jobs -------------------------

    /**
     * Called each time a job completes (successfully or not) to cause triggering of one or more follow-up jobs
     * (which may possibly the same job once over).
     *
     * @param report information about the job that just completed
     */
    public void triggerFromCompletion(JobReport report) {
        applications().lockOrThrow(report.applicationId(), application -> {
            ApplicationVersion applicationVersion = applicationVersionFrom(report);
            application = application.withJobCompletion(report, applicationVersion, clock.instant(), controller);
            application = application.withProjectId(report.projectId());

            // Handle successful starting and ending
            if (report.jobType() == JobType.component) {
                if (report.success()) {
                    if ( ! acceptNewApplicationVersionNow(application))
                        application = application.withOutstandingChange(Change.of(applicationVersion));
                    else
                        // Note that in case of an ongoing upgrade this may result in both the upgrade and application
                        // change being deployed together
                        application = application.withChange(application.change().with(applicationVersion));
                }
            }
            else if (report.jobType().isProduction() && deploymentComplete(application)) {
                // change completed
                // TODO jvenstad: Check for and remove individual parts of Change.
                application = application.withChange(Change.empty());
            }

            applications().store(application);
        });
    }

    /**
     * Find jobs that can and should run but are currently not.
     */
    public void triggerReadyJobs() {
        ApplicationList applications = ApplicationList.from(applications().asList());
        applications = applications.notPullRequest()
                                   .withProjectId()
                                   .deploying();
        for (Application application : applications.asList())
            applications().lockIfPresent(application.id(), this::triggerReadyJobs);
    }

    /**
     * Trigger a job for an application, if allowed
     *
     * @param triggering the triggering to execute, i.e., application, job type and reason
     * @return the application in the triggered state, if actually triggered. This *must* be stored by the caller
     */
    public LockedApplication trigger(Triggering triggering) {
        // Never allow untested changes to go through
        // Note that this may happen because a new change catches up and prevents an older one from continuing
        if ( ! triggering.application.deploymentJobs().isDeployableTo(triggering.jobType.environment(), triggering.application.change())) {
            log.warning(String.format("Want to trigger %s for %s with reason %s, but change is untested", triggering.jobType,
                                      triggering.application, triggering.reason));
            return triggering.application;
        }

        log.info(triggering.toString());
        deploymentQueue.addJob(triggering.application.id(), triggering.jobType, triggering.retry);
        // TODO jvenstad: Let triggering set only time of triggering (and reason, for debugging?) when build system is polled for job status.
        return triggering.application.withJobTriggering(triggering.jobType,
                                                        clock.instant(),
                                                        triggering.application.deployVersionFor(triggering.jobType, controller),
                                                        triggering.application.deployApplicationVersionFor(triggering.jobType, controller, false)
                                                                              .orElse(ApplicationVersion.unknown),
                                                        triggering.reason);
    }

    private boolean allowedToTriggerNow(Triggering triggering) {
        return allowedToTriggerNow(triggering, Collections.emptySet());
    }

    private boolean allowedToTriggerNow(Triggering triggering, Collection<JobType> concurrentlyWith) {
        if (triggering.application.deploymentJobs().isRunning(triggering.jobType, jobTimeoutLimit()))
            return false;

        if ( ! triggering.jobType.isProduction())
            return true;

        if ( ! concurrentlyWith.containsAll(JobList.from(triggering.application)
                                                   .production()
                                                   .running(jobTimeoutLimit())
                                                   .mapToList(JobStatus::type)))
            return false;

        if (triggering.application.change().blockedBy(triggering.application.deploymentSpec(), clock.instant()))
            return false;

        return true;
    }

    /**
     * Triggers a change of this application
     *
     * @param applicationId the application to trigger
     * @throws IllegalArgumentException if this application already have an ongoing change
     */
    public void triggerChange(ApplicationId applicationId, Change change) {
        applications().lockOrThrow(applicationId, application -> {
            if (application.change().isPresent() && ! application.deploymentJobs().hasFailures())
                throw new IllegalArgumentException("Could not start " + change + " on " + application + ": " +
                                                   application.change() + " is already in progress");
            application = application.withChange(change);
            if (change.application().isPresent())
                application = application.withOutstandingChange(Change.empty());

            applications().store(application);
        });
    }

    /**
     * Cancels any ongoing upgrade of the given application
     *
     * @param applicationId the application to trigger
     */
    public void cancelChange(ApplicationId applicationId, boolean keepApplicationChange) {
        applications().lockOrThrow(applicationId, application -> {
            applications().store(application.withChange(application.change().application()
                                                                   .map(Change::of)
                                                                   .filter(change -> keepApplicationChange)
                                                                   .orElse(Change.empty())));
            if ( ! applications().require(applicationId).change().isPresent())
                deploymentQueue.removeJobs(application.id());
        });
    }

    //--- End of methods which triggers deployment jobs ----------------------------

    /** Finds the next step to trigger, if any, and triggers it */
    private void triggerReadyJobs(LockedApplication application) {
        List<JobType> jobs =  order.jobsFrom(application.deploymentSpec());

        // Should the first step be triggered?
        if ( ! jobs.isEmpty() && jobs.get(0).equals(JobType.systemTest) ) {
            JobStatus systemTestStatus = application.deploymentJobs().jobStatus().get(JobType.systemTest);
            if (application.change().platform().isPresent()) {
                Version target = application.change().platform().get();
                if (     systemTestStatus == null
                    || ! systemTestStatus.lastTriggered().isPresent()
                    || ! systemTestStatus.isSuccess()
                    || ! systemTestStatus.lastTriggered().get().version().equals(target)
                    ||   systemTestStatus.isHanging(jobTimeoutLimit())) {
                    Triggering triggering = new Triggering(application, JobType.systemTest, false, "Upgrade to " + target);
                    if (allowedToTriggerNow(triggering)) {
                        application = trigger(triggering);
                        applications().store(application);
                    }
                }
            }
        }

        // Find next steps to trigger based on the state of the previous step
        for (JobType jobType : (Iterable<JobType>) Stream.concat(Stream.of(JobType.component), jobs.stream())::iterator) {
            if ( ! application.deploymentJobs().jobStatus().containsKey(jobType))
                continue; // job has never run

            // Collect the subset of next jobs which have not run with the last changes
            // TODO jvenstad: Change to be step-centric.
            List<JobType> nextJobs = order.nextAfter(jobType, application);
            for (JobType nextJobType : nextJobs) {
                JobStatus nextStatus = application.deploymentJobs().jobStatus().get(nextJobType);
                if (changesAvailable(application, jobType, nextJobType) || nextStatus.isHanging(jobTimeoutLimit())) {
                    boolean isRetry = nextStatus != null && nextStatus.jobError().filter(JobError.outOfCapacity::equals).isPresent();
                    Triggering triggering = new Triggering(application, nextJobType, isRetry, isRetry ? "Retrying on out of capacity" : "Available change in " + jobType.jobName());
                    if (allowedToTriggerNow(triggering, nextJobs))
                        application = trigger(triggering);
                }
            }
            applications().store(application);
        }
    }

    private ApplicationController applications() { return controller.applications(); }

    /** Create application version from job report */
    private ApplicationVersion applicationVersionFrom(JobReport report) {
        return report.sourceRevision().map(sr -> ApplicationVersion.from(sr, report.buildNumber()))
                     .orElse(ApplicationVersion.unknown);
    }

    /**
     * Returns true if the previous job has completed successfully with a application version and/or  Vespa version
     * which is newer (different) than the one last completed successfully in next
     */
    private boolean changesAvailable(Application application, JobType previousType, JobType nextType) {

        JobStatus next = application.deploymentJobs().jobStatus().get(nextType);

        if (next == null) return true;
        
        if ( ! application.deploymentJobs().isDeployableTo(nextType.environment(), application.change()))
            return false;

        if (next.type().isTest()) {
            // Has the upgrade test already been done?
            return ! application.deploymentJobs().isSuccessful(application.change(), next.type());
        }
        else if (next.type().isProduction()) {
            // Is the previous a job production which neither succeed with the target version, nor has a higher version?
            if (previousType.isProduction() && ! changeDeployed(application, previousType))
                return false;

            // Did the next job already succeed on the target version, or does it already have a higher version?
            if (changeDeployed(application, next.type()))
                return false;
        }
        else
            throw new IllegalStateException("Unclassified type of next job: " + next);

        return true;
    }

    /** Returns whether all production zones listed in deployment spec has this change (or a newer version, if upgrade) */
    private boolean deploymentComplete(LockedApplication application) {
        return order.jobsFrom(application.deploymentSpec()).stream()
                    .filter(JobType::isProduction)
                    .filter(job -> job.zone(controller.system()).isPresent())
                    .allMatch(job -> changeDeployed(application, job));
    }

    /**
     * Returns whether the given application should skip deployment of its current change to the given production job zone.
     *
     * If the currently deployed application has a newer platform or application version than the application's
     * current change, the method returns {@code true}, to avoid a downgrade.
     * Otherwise, it returns whether the current change is redundant, i.e., all its components are already deployed.
     */
    private boolean changeDeployed(Application application, JobType job) {
        if ( ! job.isProduction())
            throw new IllegalArgumentException(job + " is not a production job!");

        Deployment deployment = application.deployments().get(job.zone(controller.system()).get());
        if (deployment == null)
            return false;

        int applicationComparison = application.change().application()
                                               .map(version -> version.compareTo(deployment.applicationVersion()))
                                               .orElse(0);

        int platformComparion = application.change().platform()
                                           .map(version -> version.compareTo(deployment.version()))
                                           .orElse(0);

        if (applicationComparison == -1 || platformComparion == -1)
            return true; // Avoid downgrades!

        return applicationComparison == 0 && platformComparion == 0;
    }

    private boolean acceptNewApplicationVersionNow(LockedApplication application) {
        if ( ! application.change().isPresent()) return true;

        if (application.change().application().isPresent()) return true; // more application changes are ok

        if (application.deploymentJobs().hasFailures()) return true; // allow changes to fix upgrade problems

        if (application.isBlocked(clock.instant())) return true; // allow testing changes while upgrade blocked (debatable)

        // Otherwise, the application is currently upgrading, without failures, and we should wait with the new
        // application version.
        return false;
    }

    public static class Triggering {

        private final LockedApplication application; // TODO jvenstad: Consider passing an ID instead.
        private final JobType jobType;
        private final boolean retry;
        private final String reason;

        public Triggering(LockedApplication application, JobType jobType, boolean retry, String reason) {
            this.application = application;
            this.jobType = jobType;
            this.retry = retry;
            this.reason = reason;
        }

        public LockedApplication application() {
            return application;
        }

        public JobType jobType() {
            return jobType;
        }

        public boolean isRetry() {
            return retry;
        }

        public String reason() {
            return reason;
        }

        public String toString() {
            return String.format("Triggering %s for %s, %s: %s",
                                 jobType,
                                 application,
                                 application.change().isPresent() ? "deploying " + application.change() : "restarted deployment",
                                 reason);
        }

    }

}
