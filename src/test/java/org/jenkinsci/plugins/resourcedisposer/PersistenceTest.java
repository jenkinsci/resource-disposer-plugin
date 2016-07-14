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

import hudson.FilePath;
import jenkins.model.Jenkins;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class PersistenceTest {
    @Rule
    public RestartableJenkinsRule j = new RestartableJenkinsRule();

    @Test
    public void persistEntries() throws Exception {
        j.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                AsyncResourceDisposer disposer = AsyncResourceDisposer.get();
                disposer.dispose(new FailingDisposable());
                checkItem(disposer.getBacklog());
            }
        });
        j.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                AsyncResourceDisposer disposer = AsyncResourceDisposer.get();
                checkItem(disposer.getBacklog());
            }
        });
    }

    private void checkItem(Set<AsyncResourceDisposer.WorkItem> backlog) {
        assertThat(backlog, Matchers.<AsyncResourceDisposer.WorkItem>iterableWithSize(1));
        AsyncResourceDisposer.WorkItem item = backlog.iterator().next();
        assertThat(item.getDisposable(), Matchers.instanceOf(FailingDisposable.class));
        assertThat(item.getLastState(), Matchers.instanceOf(AsyncResourceDisposer.WorkItem.Failed.class));
        AsyncResourceDisposer.WorkItem.Failed failure = (AsyncResourceDisposer.WorkItem.Failed) item.getLastState();
        assertThat(failure.getCause().getMessage(), equalTo(FailingDisposable.EXCEPTION.getMessage()));
    }
}
