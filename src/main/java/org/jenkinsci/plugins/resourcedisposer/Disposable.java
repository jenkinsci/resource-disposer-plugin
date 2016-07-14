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
 * @author ogondza
 * @see AsyncResourceDisposer#dispose(Disposable)
 */
public interface Disposable extends Serializable {

    /**
     * Dispose the resource.
     *
     * @return State of the resource after the attempt.
     * @throws Exception Problem disposing the resource.
     */
    @Nonnull State dispose() throws Exception;

    /**
     * Text description of the disposable.
     *
     * Admins are supposed to understand what kind of resource and what exact
     * resource is being disposed from this description.
     */
    @Nonnull String getDisplayName();

    abstract class State implements Serializable {
        public static final @Nonnull State DISPOSE = new Dispose();
        public static final @Nonnull State PURGED = new Purged();

        private final String displayName;

        protected State(String displayName) {
            this.displayName = displayName;
        }

        public @Nonnull String getDisplayName() {
            return displayName;
        }

        public static class Purged extends State {
            protected Purged() {
                super("Purged successfully");
            }
        }

        public static class Dispose extends State {
            protected Dispose() {
                super("To dispose");
            }
        }
    }

//        public static final class Register extends SlaveToMasterCallable<Boolean, RuntimeException> {
//            private static final long serialVersionUID = 1L;
//
//            private final Disposable disposable;
//
//            public Register(Disposable disposable) {
//                this.disposable = disposable;
//            }
//
//            /**
//             * Register disposable to {@link AsyncResourceDisposer} over channel from slave.
//             *
//             * @return false if failed to register because we are not talking to master.
//             */
//            public final Boolean call() throws RuntimeException {
//                if (Jenkins.getInstance() == null) return false;
//
//                AsyncResourceDisposer disposer = AdministrativeMonitor.all().get(AsyncResourceDisposer.class);
//                disposer.dispose(disposable);
//                return true;
//            }
//        }
}
