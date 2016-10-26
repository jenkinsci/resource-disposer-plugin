/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
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

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Wrapper for a resource to be deleted.
 *
 * {@link #dispose()} is called periodically on the resource until
 * {@link State#PURGED} is returned. Exception thrown by the method will be
 * kept and reported to the administrator.
 *
 * Implementation should report success even in case the resource got disposed
 * externally. This is expected in case resource get disposed by administrator
 * after failed attempts was reported.
 *
 * In case the resource is external to Jenkins and can survive Jenkins restart,
 * The implementation needs to be able to correctly locate the resource once deserialized.
 *
 * As long as the Disposable is used by {@link AsyncResourceDisposer} only, it is
 * guaranteed to never run in more than one thread at a time.
 *
 * @author ogondza
 * @see AsyncResourceDisposer#dispose
 */
public interface Disposable extends Serializable {

    /**
     * Dispose the resource.
     *
     * @return State of the resource after the attempt. {@link State#PURGED} in case the resource do not need to be tracked any longer.
     * @throws Throwable Problem disposing the resource. The exception thrown will be reported as a reason the dispose attempt failed.
     */
    @Nonnull State dispose() throws Throwable;

    /**
     * Text description of the disposable.
     *
     * @return String providing enough of a hint for admin to know the resource
     * kind and identity. Ex.: "Docker container my/tag"
     */
    @Nonnull String getDisplayName();

    abstract class State implements Serializable {
        public static final @Nonnull State TO_DISPOSE = new ToDispose();
        public static final @Nonnull State PURGED = new Purged();

        private final String displayName;

        protected State(String displayName) {
            this.displayName = displayName;
        }

        public @Nonnull String getDisplayName() {
            return displayName;
        }

        public static final class Purged extends State {
            private Purged() {
                super("Purged successfully");
            }
        }

        public static final class ToDispose extends State {
            private ToDispose() {
                super("To dispose");
            }
        }

        public static final class Thrown extends Disposable.State {
            private final @Nonnull Throwable cause;

            public Thrown(@Nonnull Throwable cause) {
                super(cause.getMessage());
                this.cause = cause;
            }

            @Nonnull public Throwable getCause() {
                return cause;
            }
        }

        public static final class Failed extends Disposable.State {
            public Failed(@Nonnull String cause) {
                super(cause);
            }
        }
    }
}
