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

import com.gargoylesoftware.htmlunit.FailingHttpStatusCodeException;
import com.gargoylesoftware.htmlunit.HttpMethod;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import hudson.model.AdministrativeMonitor;
import hudson.util.OneShotEvent;
import jenkins.model.Jenkins;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.mockito.internal.util.reflection.Whitebox;

import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyCollectionOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.iterableWithSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class AsyncResourceDisposerTest {

    @Rule public final JenkinsRule j = new JenkinsRule();

    private AsyncResourceDisposer disposer;

    @Before
    public void setUp() {
        // Replace existing disposer with new one to prevent Disposable leak between test runs
        List<AdministrativeMonitor> administrativeMonitors = j.jenkins.administrativeMonitors;
        administrativeMonitors.clear();
        AsyncResourceDisposer nard = new AsyncResourceDisposer();
        administrativeMonitors.add(0, nard);
        disposer = AsyncResourceDisposer.get();
    }

    @Test
    public void disposeImmediately() throws Throwable {
        Disposable disposable = mock(Disposable.class);
        when(disposable.dispose()).thenReturn(Disposable.State.PURGED);

        disposer.disposeAndWait(disposable).get();

        verify(disposable, times(1)).dispose();
        assertThat(disposer.getBacklog(), empty());
        assertFalse(disposer.isActivated());
    }

    @Test
    public void neverDispose() throws Throwable {
        final IOException error = new IOException("to be thrown");

        Disposable disposable = spy(new ThrowDisposable(error));

        @SuppressWarnings("deprecation")
        AsyncResourceDisposer.WorkItem item = disposer.disposeAndWait(disposable).get();

        Set<AsyncResourceDisposer.WorkItem> remaining = disposer.getBacklog();
        assertEquals(1, remaining.size());
        assertThat(remaining.iterator().next(), equalTo(item));
        assertEquals(error, ((Disposable.State.Thrown) item.getLastState()).getCause());

        verify(disposable).dispose();
        assertThat(disposer.getBacklog(), not(empty()));

        int itemId = item.getId();
        HtmlPage page = j.createWebClient().goTo(disposer.getUrl());
        page = page.getFormByName("stop-tracking-" + itemId).getInputByName("submit").click();
        assertThat(page.getWebResponse().getContentAsString(), containsString(disposer.getDisplayName())); // Redirected back

        assertThat(disposer.getBacklog(), emptyCollectionOf(AsyncResourceDisposer.WorkItem.class));
    }

    private static class ThrowDisposable implements Disposable {
        private static final long serialVersionUID = -6079270961651246596L;

        private Throwable ex;

        ThrowDisposable(Throwable ex) {
            this.ex = ex;
        }

        @Override
        public @Nonnull State dispose() throws Throwable {
            throw ex;
        }

        @Override
        public @Nonnull String getDisplayName() {
            return "Throwing";
        }
    }

    @Test
    public void postponedDisposal() throws Throwable {
        Disposable disposable = mock(Disposable.class);
        when(disposable.dispose()).thenReturn(
                Disposable.State.TO_DISPOSE, Disposable.State.TO_DISPOSE, Disposable.State.PURGED
        );

        disposer.disposeAndWait(disposable).get();
        disposer.rescheduleAndWait();
        disposer.rescheduleAndWait();

        assertThat(disposer.getBacklog(), empty());
        verify(disposable, times(3)).dispose();
    }

    @Test
    public void combined() throws Throwable {

        Disposable noProblem = mock(Disposable.class, withSettings().serializable());
        when(noProblem.dispose()).thenReturn(Disposable.State.PURGED);

        final IOException error = new IOException("to be thrown");
        Disposable problem = mock(Disposable.class, withSettings().serializable());
        when(problem.dispose()).thenThrow(error);

        Disposable postponed = mock(Disposable.class, withSettings().serializable());
        when(postponed.dispose()).thenReturn(
                Disposable.State.TO_DISPOSE, Disposable.State.TO_DISPOSE, Disposable.State.PURGED
        );

        disposer.disposeAndWait(noProblem).get();
        disposer.disposeAndWait(problem).get();
        disposer.disposeAndWait(noProblem).get();
        disposer.disposeAndWait(postponed).get();

        disposer.rescheduleAndWait();
        disposer.rescheduleAndWait();

        verify(noProblem, times(2)).dispose();
        //verify(problem, atLeast(3)).dispose();
        verify(postponed, times(3)).dispose();
        assertEquals(1, disposer.getBacklog().size());
        assertEquals(problem, disposer.getBacklog().iterator().next().getDisposable());
    }

    @Test
    public void showProblems() throws Exception {
        disposer = AsyncResourceDisposer.get();
        disposer.dispose(new FailingDisposable());

        Thread.sleep(1000);

        JenkinsRule.WebClient wc = j.createWebClient();

        assertFalse(disposer.isActivated());
        HtmlPage manage = wc.goTo("manage");
        assertThat(manage.asText(), not(containsString("There are resources Jenkins was not able to dispose automatically")));

        Whitebox.setInternalState(disposer.getBacklog().iterator().next(), "registered", new Date(0)); // Make it decades old

        assertTrue(disposer.isActivated());
        manage = wc.goTo("manage");
        assertThat(manage.asText(), containsString("There are resources Jenkins was not able to dispose automatically"));
        HtmlPage report = wc.goTo(disposer.getUrl());
        assertThat(report.asText(), containsString("Failing disposable"));
        assertThat(report.asText(), containsString("IOException: Unable to dispose"));
    }

    @Test
    public void collapseSameDisposables() {
        disposer = AsyncResourceDisposer.get();

        // Identical insatnces collapses
        FailingDisposable fd = new FailingDisposable();
        disposer.dispose(fd);
        assertThat(disposer.getBacklog(), iterableWithSize(1));
        disposer.dispose(fd);
        assertThat(disposer.getBacklog(), iterableWithSize(1));

        // Equal instances collapses
        disposer.dispose(new SameDisposable());
        assertThat(disposer.getBacklog(), iterableWithSize(2));
        disposer.dispose(new SameDisposable());
        disposer.dispose(new SameDisposable());
        disposer.dispose(new SameDisposable());
        assertThat(disposer.getBacklog(), iterableWithSize(2));

        // Not-equal implementations does not collapse
        FailingDisposable otherFailingDisposable = new FailingDisposable();
        disposer.dispose(otherFailingDisposable);
        assertThat(disposer.getBacklog(), iterableWithSize(3));
        disposer.dispose(otherFailingDisposable);
        assertThat(disposer.getBacklog(), iterableWithSize(3));
    }
    private static final class SameDisposable extends FailingDisposable {
        private static final long serialVersionUID = 6769179986158394005L;

        @Override public int hashCode() {
            return 73;
        }

        @Override public boolean equals(Object obj) {
            return obj instanceof SameDisposable;
        }
    }

    @Test @Issue("SECURITY-997")
    public void security997() throws Exception {
        j.jenkins.setCrumbIssuer(null);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        MockAuthorizationStrategy mas = new MockAuthorizationStrategy();
        mas.grant(Jenkins.READ).everywhere().to("user");
        mas.grant(Jenkins.ADMINISTER).everywhere().to("admin");
        j.jenkins.setAuthorizationStrategy(mas);

        String disposerUrl = j.getURL() + disposer.getUrl();
        URL actionUrl = new URL(disposerUrl + "/stopTracking/?id=42");
        JenkinsRule.WebClient uwc = j.createWebClient().login("user", "user");

        try {
            uwc.getPage(disposerUrl);
            fail();
        } catch (FailingHttpStatusCodeException ex) {
            assertEquals(HttpServletResponse.SC_FORBIDDEN, ex.getResponse().getStatusCode());
        }

        try {
            uwc.getPage(new WebRequest(actionUrl, HttpMethod.POST));
            fail();
        } catch (FailingHttpStatusCodeException ex) {
            assertEquals(HttpServletResponse.SC_FORBIDDEN, ex.getResponse().getStatusCode());
        }

        try {
            uwc.getPage(actionUrl);
            fail();
        } catch (FailingHttpStatusCodeException ex) {
            // Never jenkins/stapler version reversed the order of checks apparently
            int statusCode = ex.getResponse().getStatusCode();
            assertThat(statusCode, anyOf(equalTo(HttpServletResponse.SC_METHOD_NOT_ALLOWED), equalTo(HttpServletResponse.SC_FORBIDDEN)));
        }

        JenkinsRule.WebClient awc = j.createWebClient().login("admin", "admin");
        awc.getPage(disposerUrl);
        awc.getPage(new WebRequest(actionUrl, HttpMethod.POST));

        try {
            awc.getPage(actionUrl);
            fail();
        } catch (FailingHttpStatusCodeException ex) {
            assertEquals(HttpServletResponse.SC_METHOD_NOT_ALLOWED, ex.getResponse().getStatusCode());
        }
    }

    @Test
    public void concurrentDisposablesAreThrottled() throws InterruptedException {
        disposer = AsyncResourceDisposer.get();
        final int MPS = AsyncResourceDisposer.MAXIMUM_POOL_SIZE;

        // Almost fill the pool
        for (int i = 0; i < MPS - 1; i++) {
            disposer.dispose(new BlockingDisposable());
        }

        assertThat(getActive(disposer).size(), equalTo(MPS - 1));
        assertThat(disposer.getBacklog().size(), equalTo(MPS - 1));
        disposer.dispose(new BlockingDisposable());
        assertThat(getActive(disposer).size(), equalTo(MPS));
        assertThat(disposer.getBacklog().size(), equalTo(MPS));
        disposer.dispose(new BlockingDisposable());
        assertThat(getActive(disposer).size(), equalTo(MPS));
        assertThat(disposer.getBacklog().size(), equalTo(MPS + 1));

        getActive(disposer).get(0).end.signal();
        disposer.reschedule();
        Thread.sleep(500);
        assertThat(getActive(disposer).size(), equalTo(MPS));
        assertThat(disposer.getBacklog().size(), equalTo(MPS));

        getActive(disposer).get(0).end.signal();
        disposer.reschedule();
        Thread.sleep(500);
        assertThat(getActive(disposer).size(), equalTo(MPS - 1));
        assertThat(disposer.getBacklog().size(), equalTo(MPS - 1));
    }

    private ArrayList<BlockingDisposable> getActive(AsyncResourceDisposer disposer) {
        ArrayList<BlockingDisposable> bds = new ArrayList<>();
        for (AsyncResourceDisposer.WorkItem wa: disposer.getBacklog()) {
            BlockingDisposable disposable = (BlockingDisposable) wa.getDisposable();
            if (disposable.isActive()) {
                bds.add(disposable);
            }
        }
        return bds;
    }

    private static final class BlockingDisposable implements Disposable {
        private static final long serialVersionUID = 4648005477636912909L;
        private final OneShotEvent start = new OneShotEvent();
        private final OneShotEvent end = new OneShotEvent();

        @Nonnull @Override public State dispose() throws Throwable {
            start.signal();
            end.block();
            return State.PURGED;
        }

        @Nonnull @Override public String getDisplayName() {
            return "Blocked " + hashCode();
        }

        private boolean isActive() {
            return start.isSignaled() && !end.isSignaled();
        }
    }
}
