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

import java.io.Serializable;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import hudson.model.PeriodicWork;
import hudson.util.DaemonThreadFactory;
import hudson.util.ExceptionCatchingThreadFactory;
import hudson.util.NamingThreadFactory;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.Nonnull;
import javax.inject.Inject;

/**
 * Track resources to be disposed asynchronously.
 *
 * In order to have resource disposed safely and eventual failures tracked for
 * Jenkins admins to see, register wrapped resources uisng {@link #dispose(Disposable)}.
 *
 * @author ogondza
 * @see Disposable
 */
public class AsyncResourceDisposer extends AdministrativeMonitor {

    @Extension @Restricted(NoExternalUse.class)
    public static final AsyncResourceDisposer ard = new AsyncResourceDisposer();

    @Extension @Restricted(NoExternalUse.class)
    public static final Scheduler scheduler = new Scheduler(ard);

    private static final ThreadPoolExecutor worker = new ThreadPoolExecutor (
            0, 1, 5L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
            new ExceptionCatchingThreadFactory(new NamingThreadFactory(new DaemonThreadFactory(), "AsyncResourceDisposer"))
    );

    /**
     * Persist all entries to dispose in order to survive restart.
     */
    private final @Nonnull Set<WorkItem> backlog = Collections.newSetFromMap(new ConcurrentHashMap<WorkItem, Boolean>());

    public static @Nonnull AsyncResourceDisposer get() {
        return Jenkins.getInstance().getExtensionList(AsyncResourceDisposer.class).get(0);
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
     */
    public void dispose(final @Nonnull Disposable disposable) {
        WorkItem item = new WorkItem(this, disposable);
        backlog.add(item);
        submit(item);
    }

    private void submit(WorkItem item) {
        if (item.inProgress) {
            System.out.println("Skipping " + item.getDisposable() + " as already in progress");
            return;
        }
        System.out.println("Scheduling " + item.getDisposable() + " while " + worker.getQueue());
        worker.submit(item);
    }

    /**
     * Disposable wrapper to be run in threadpool and track success/failure.
     */
    @Restricted(NoExternalUse.class)
    public static final class WorkItem implements Runnable, Serializable {
        private final @Nonnull AsyncResourceDisposer disposer;

        private final @Nonnull Disposable disposable;
        private final @Nonnull Date registered = new Date();
        private transient @Nonnull Disposable.State lastState = Disposable.State.DISPOSE;
        private transient boolean inProgress = false;

        private WorkItem(AsyncResourceDisposer disposer, Disposable disposable) {
            this.disposer = disposer;
            this.disposable = disposable;
        }

        public @Nonnull Disposable getDisposable() {
            return disposable;
        }

        public @Nonnull Date getRegistered() {
            return registered;
        }

        public @Nonnull Disposable.State getLastState() {
            return lastState;
        }

        @Override
        public String toString() {
            return "Disposer work item: " + disposable;
        }

        public void run() {
            inProgress = true;
            try {
                lastState = disposable.dispose();
                if (lastState == Disposable.State.PURGED) {
                    disposer.backlog.remove(this);
                }
            } catch (Throwable ex) {
                lastState = new Failed(ex);
            } finally {
                inProgress = false;
            }
        }

        public static final class Failed extends Disposable.State {
            private final @Nonnull Throwable cause;

            private Failed(@Nonnull Throwable cause) {
                super(cause.getMessage());
                this.cause = cause;
            }

            @Nonnull public Throwable getCause() {
                return cause;
            }
        }
    }

    /**
     * Get list of resources do be disposed.
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

    /**
     * Reschedule failed attempts.
     *
     * @author ogondza
     */
    @Restricted(NoExternalUse.class)
    public static class Scheduler extends PeriodicWork {

        private AsyncResourceDisposer disposer;

        @Inject // Needed to make guice happy
        public Scheduler(AsyncResourceDisposer disposer) {
            this.disposer = disposer;
        }

        @Override
        public void doRun() throws Exception {
            for (WorkItem workItem: disposer.getBacklog()) {
                disposer.submit(workItem);
            }
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
