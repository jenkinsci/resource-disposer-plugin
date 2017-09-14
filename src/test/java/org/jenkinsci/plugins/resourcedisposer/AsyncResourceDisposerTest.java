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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyCollectionOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.Date;
import java.util.Set;

import com.gargoylesoftware.htmlunit.html.HtmlPage;
import org.hamcrest.Matchers;
import org.hamcrest.collection.IsEmptyCollection;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.internal.util.reflection.Whitebox;

import javax.annotation.Nonnull;

public class AsyncResourceDisposerTest {

    @Rule public final JenkinsRule j = new JenkinsRule();

    private AsyncResourceDisposer disposer;

    @Before
    public void setUp() {
        disposer = AsyncResourceDisposer.get();
    }

    @Test
    public void disposeImmediately() throws Throwable {
        Disposable disposable = mock(Disposable.class);
        when(disposable.dispose()).thenReturn(Disposable.State.PURGED);

        disposer.dispose(disposable);

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
        assertThat(disposer.getBacklog(), not(IsEmptyCollection.<AsyncResourceDisposer.WorkItem>empty()));

        int itemId = item.getId();
        HtmlPage page = j.createWebClient().goTo(disposer.getUrl());
        page = page.getFormByName("stop-tracking-" + itemId).getInputByName("submit").click();
        assertThat(page.getWebResponse().getContentAsString(), containsString(disposer.getDisplayName())); // Redirected back

        assertThat(disposer.getBacklog(), emptyCollectionOf(AsyncResourceDisposer.WorkItem.class));
    }

    private static class ThrowDisposable implements Disposable {

        private Throwable ex;

        public ThrowDisposable(Throwable ex) {
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

        disposer.dispose(disposable);
        Thread.sleep(100);
        disposer.reschedule();
        Thread.sleep(100);
        disposer.reschedule();
        Thread.sleep(100);

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

        disposer.dispose(noProblem);
        disposer.dispose(problem);
        disposer.dispose(noProblem);
        disposer.dispose(postponed);

        Thread.sleep(100);
        disposer.reschedule();
        Thread.sleep(100);
        disposer.reschedule();
        Thread.sleep(100);

        verify(noProblem, times(2)).dispose();
        verify(problem, atLeast(3)).dispose();
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
        assertThat(disposer.getBacklog(), Matchers.<AsyncResourceDisposer.WorkItem>iterableWithSize(1));
        disposer.dispose(fd);
        assertThat(disposer.getBacklog(), Matchers.<AsyncResourceDisposer.WorkItem>iterableWithSize(1));

        // Equal instances collapses
        disposer.dispose(new SameDisposable());
        assertThat(disposer.getBacklog(), Matchers.<AsyncResourceDisposer.WorkItem>iterableWithSize(2));
        disposer.dispose(new SameDisposable());
        disposer.dispose(new SameDisposable());
        disposer.dispose(new SameDisposable());
        assertThat(disposer.getBacklog(), Matchers.<AsyncResourceDisposer.WorkItem>iterableWithSize(2));

        // Not-equal implementations does not collapse
        FailingDisposable otherFailingDisposable = new FailingDisposable();
        disposer.dispose(otherFailingDisposable);
        assertThat(disposer.getBacklog(), Matchers.<AsyncResourceDisposer.WorkItem>iterableWithSize(3));
        disposer.dispose(otherFailingDisposable);
        assertThat(disposer.getBacklog(), Matchers.<AsyncResourceDisposer.WorkItem>iterableWithSize(3));
    }
    private static final class SameDisposable extends FailingDisposable {
        @Override public int hashCode() {
            return 73;
        }

        @Override public boolean equals(Object obj) {
            return obj instanceof SameDisposable;
        }
    }
}
