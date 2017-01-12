/*
 * The MIT License
 *
 * Copyright (c) 2015 Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.resourcedisposer;

import java.io.File;
import java.io.IOException;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import hudson.Extension;
import hudson.XmlFile;
import hudson.model.AdministrativeMonitor;
import hudson.model.Computer;
import hudson.model.PeriodicWork;
import hudson.util.HttpResponses;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * Track resources to be disposed asynchronously.
 *
 * In order to have resource disposed safely and eventual failures tracked for
 * Jenkins admins to see, register wrapped resources using {@link #dispose}.
 *
 * @author ogondza
 * @see Disposable
 */
@Extension
public class AsyncResourceDisposer extends AdministrativeMonitor implements Serializable {

    private static final ExecutorService worker = Computer.threadPoolForRemoting;
    private static final Logger LOGGER = Logger.getLogger(AsyncResourceDisposer.class.getName());
    /*new ThreadPoolExecutor (
            0, 1, 5L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
            new ExceptionCatchingThreadFactory(new NamingThreadFactory(new DaemonThreadFactory(), "AsyncResourceDisposer"))
    );*/

    /**
     * Persist all entries to dispose in order to survive restart.
     */
    private final @Nonnull Set<WorkItem> backlog = Collections.newSetFromMap(new ConcurrentHashMap<WorkItem, Boolean>());

    public static @Nonnull AsyncResourceDisposer get() {
        Jenkins instance = Jenkins.getInstance();
        if (instance == null) throw new IllegalStateException();
        return (AsyncResourceDisposer) instance.getAdministrativeMonitor("AsyncResourceDisposer");
    }

    public AsyncResourceDisposer() {
        super("AsyncResourceDisposer");
        load();
    }

    /**
     * Get list of resources do be disposed.
     *
     * @return Entries to be processed.
     */
    public @Nonnull Set<WorkItem> getBacklog() {
        synchronized (backlog) {
            return new HashSet<WorkItem>(backlog);
        }
    }

    @Override
    public String getDisplayName() {
        return "Asynchronous resource disposer";
    }

    @Override
    public boolean isActivated() {
        for (WorkItem workItem: getBacklog()) {
            if (!workItem.getLastState().equals(Disposable.State.PURGED)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Schedule resource to be disposed.
     *
     * @param disposables Resource wrappers to be deleted.
     */
    public void dispose(final @Nonnull Disposable... disposables) {
        for (Disposable disposable : disposables) {
            WorkItem item = new WorkItem(this, disposable);
            backlog.add(item);
            worker.submit(item);
        }
        persist();
    }

    /**
     * Schedule resource to be disposed.
     *
     * @param disposables Resource wrappers to be deleted.
     */
    public void dispose(final @Nonnull Iterable<Disposable> disposables) {
        for (Disposable disposable : disposables) {
            WorkItem item = new WorkItem(this, disposable);
            backlog.add(item);
            worker.submit(item);
        }
        persist();
    }

    @Restricted(DoNotUse.class)
    public HttpResponses.HttpResponseException doStopTracking(@QueryParameter int id) {
        for (WorkItem workItem : getBacklog()) {
            if (workItem.getId() == id) {
                backlog.remove(workItem);
                break;
            }
        }
        return HttpResponses.forwardToPreviousPage();
    }

    /**
     * Force rescheduling of all tracked tasks.
     *
     * @deprecated Only exposed for testing.
     */
    @Deprecated
    public void reschedule() {
        for (WorkItem workItem: getBacklog()) {
            if (workItem.inProgress) {
                // No need to reschedule
                LOGGER.fine(workItem + " is in progress");
            } else {
                LOGGER.finer("Rescheduling " + workItem);
                worker.submit(workItem);
            }
        }
    }

    /**
     * Dispose providing Future to wait for first dispose cycle to complete.
     *
     * @deprecated Only exposed for testing.
     */
    @Deprecated
    public Future<WorkItem> disposeAndWait(Disposable disposable) {
        WorkItem item = new WorkItem(this, disposable);
        backlog.add(item);
        Future<WorkItem> future = worker.submit(item, item);
        persist();
        return future;
    }

    private void persist() {
        try {
            getConfigFile().write(this);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to store AsyncResourceDisposer history", e);
        }
    }

    private void load() {
        final XmlFile file = getConfigFile();
        if (file.exists()) {
            try {
                file.unmarshal(this);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Unable to load AsyncResourceDisposer history", e);
            }
        }
    }

    private XmlFile getConfigFile() {
        Jenkins instance = Jenkins.getInstance();
        if (instance == null) throw new IllegalStateException();
        return new XmlFile(Jenkins.XSTREAM, new File(new File(
                instance.root,
                getClass().getCanonicalName() + ".xml"
        ).getAbsolutePath()));
    }

    /**
     * Disposable wrapper to be run in threadpool and track success/failure.
     */
    @Restricted(NoExternalUse.class)
    public static final class WorkItem implements Runnable, Serializable {
        private static final long serialVersionUID = 1L;

        private final @Nonnull AsyncResourceDisposer disposer;

        private /*final*/ @Nonnull Disposable disposable;
        private final @Nonnull Date registered = new Date();
        // There is no reason to serialized something more elaborate as after restart it will either succeed or fail again farly soon.
        private volatile @Nonnull Disposable.State lastState = Disposable.State.TO_DISPOSE;
        private volatile transient boolean inProgress;

        private @CheckForNull String disposableInfo;

        private WorkItem(@Nonnull AsyncResourceDisposer disposer, @Nonnull Disposable disposable) {
            this.disposer = disposer;
            this.disposable = disposable;
            readResolve();
        }

        private Object readResolve() {
            inProgress = false;
            if (disposable == null) {
                final String msg = "Unable to deserialize '" + disposableInfo + "'. The resource was probably leaked.";
                LOGGER.warning(msg);
                disposable = new FailedToDeserialize(msg);
            }
            disposableInfo = null;
            return this;
        }

        // Save disposable details to report in case we fail to deserialize the disposable
        private Object writeReplace() throws ObjectStreamException {
            disposableInfo = disposable.getClass().getName() + ":" + disposable.getDisplayName();
            return this;
        }

        public @Nonnull Disposable getDisposable() {
            return disposable;
        }

        public @Nonnull Date getRegistered() {
            return new Date(registered.getTime());
        }

        public @Nonnull Disposable.State getLastState() {
            return lastState;
        }

        public int getId() {
            return System.identityHashCode(this);
        }

        @Override
        public String toString() {
            return "Disposer work item: " + disposable.getDisplayName();
        }

        public void run() {
            // Never run more than once at a time
            if (inProgress) return;

            inProgress = true;
            try {
                lastState = disposable.dispose();
                if (lastState == Disposable.State.PURGED) {
                    disposer.backlog.remove(this);
                }
            } catch (Throwable ex) {
                lastState = new Disposable.State.Thrown(ex);
            } finally {
                inProgress = false;
                disposer.persist();
            }
        }

        private static class FailedToDeserialize implements Disposable {
            private static final long serialVersionUID = 5249985901013332487L;
            private final String msg;

            FailedToDeserialize(String msg) {
                this.msg = msg;
            }

            @Override
            public @Nonnull State dispose() throws Throwable {
                return State.PURGED;
            }

            @Override
            public @Nonnull String getDisplayName() {
                return msg;
            }
        }
    }

    /**
     * Reschedule failed attempts.
     *
     * @author ogondza
     */
    @Restricted(DoNotUse.class)
    @Extension
    public static class Scheduler extends PeriodicWork {

        @Override
        public void doRun() throws Exception {
            //noinspection deprecation
            AsyncResourceDisposer.get().reschedule();
        }

        @Override
        public long getRecurrencePeriod() {
            return MIN;
        }

        @Override
        public String toString() {
            return "AsyncResourceDisposer.Maintainer";
        }
    }
}
