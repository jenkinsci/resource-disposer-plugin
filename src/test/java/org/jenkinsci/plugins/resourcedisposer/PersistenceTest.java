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

import com.gargoylesoftware.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.resourcedisposer.Disposable.State.Thrown;
import org.jenkinsci.plugins.resourcedisposer.Disposable.State.ToDispose;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsSessionRule;

import javax.annotation.Nonnull;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.iterableWithSize;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class PersistenceTest {
    @Rule
    public JenkinsSessionRule sessions = new JenkinsSessionRule();

    @Test
    public void persistEntries() throws Throwable {
        sessions.then(r -> {
                AsyncResourceDisposer disposer = AsyncResourceDisposer.get();
                disposer.disposeAndWait(new FailingDisposable()).get();
                AsyncResourceDisposer.WorkItem item = checkItem(disposer.getBacklog());

                assertThat(item.getLastState(), instanceOf(Thrown.class));
                Thrown failure = (Thrown) item.getLastState();
                assertThat(failure.getCause().getMessage(), equalTo(FailingDisposable.EXCEPTION.getMessage()));
        });
        sessions.then(r -> {
                AsyncResourceDisposer disposer = AsyncResourceDisposer.get();
                AsyncResourceDisposer.WorkItem item = checkItem(disposer.getBacklog());

                Disposable.State lastState = item.getLastState();
                // It is ok if this is not deserialized 100% same
                assertTrue("Failed or ready to be rechecked", (lastState instanceof ToDispose) || (lastState instanceof Thrown));
        });
    }

    @Test
    public void recoverWhenDisposableCanNotBeDeserialized() throws Throwable {
        sessions.then(r -> {
                AsyncResourceDisposer disposer = AsyncResourceDisposer.get();
                disposer.dispose(new DisappearingDisposable());
                assertThat(disposer.getBacklog(), iterableWithSize(1));
        });
        sessions.then(r -> {
                AsyncResourceDisposer disposer = AsyncResourceDisposer.get();
                // Current implementation will remove it so the backlog will be empty - either way NPE should not be thrown
                for (AsyncResourceDisposer.WorkItem item : disposer.getBacklog()) {
                    assertNotEquals(null, item.getDisposable());
                    item.toString();
                }

                JenkinsRule.WebClient wc = r.createWebClient();
                HtmlPage page = wc.goTo("administrativeMonitor/AsyncResourceDisposer");
                assertThat(page.getWebResponse().getContentAsString(), containsString("Asynchronous resource disposer"));
                assertThat(page.getWebResponse().getContentAsString(), not(containsString("Exception")));
        });
    }
    private static final class DisappearingDisposable implements Disposable {
        private static final long serialVersionUID = 3007902823296336222L;

        @Override
        public @Nonnull State dispose() {
            return State.TO_DISPOSE;
        }

        @Override
        public @Nonnull String getDisplayName() {
            return "Will disappear after restart";
        }

        private Object readResolve() {
            // Simulate error while deserializing like when the plugin gets uninstalled
            return null;
        }
    }

    private AsyncResourceDisposer.WorkItem checkItem(Set<AsyncResourceDisposer.WorkItem> backlog) {
        assertThat(backlog, iterableWithSize(1));
        AsyncResourceDisposer.WorkItem item = backlog.iterator().next();
        assertThat(item.getDisposable(), instanceOf(FailingDisposable.class));
        return item;
    }
}
