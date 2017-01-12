/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Red Hat, Inc.
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

package org.jenkinsci.plugins.resourcedisposer

import java.text.DateFormat
import java.text.SimpleDateFormat

def l = namespace(lib.LayoutTagLib)
def st = namespace("jelly:stapler")

l.layout(permission: app.ADMINISTER) {
    l.header(title: my.displayName)
    st.include(page: "sidepanel", it: app)
    l.main_panel {
        h1(my.displayName)
        table(class: "sortable bigtable", style: "width: 100%") {
            tr {
                th(_("Resource"))
                th(_("Tracked since"))
                th(_("State"))
                th("&nbsp;")
            }
            DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            for (AsyncResourceDisposer.WorkItem item in my.backlog) {
                tr {
                    td(item.disposable.displayName)
                    td{
                        st.include(page: "cell", it: item.lastState)
                    }
                    td(df.format(item.registered))
                    td{
                        form(action: "./stopTracking", method: "POST", name: "stop-tracking-" + item.id) {
                            input(name: "id", type: "hidden", value: item.id)
                            input(name: "submit", type: "submit", value: _("Stop tracking"))
                        }
                    }
                }
            }
        }
    }
}
