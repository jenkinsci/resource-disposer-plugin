# Jenkins Resource Disposer Plugin
[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/resource-disposer.svg?color=blue)](https://plugins.jenkins.io/resource-disposer)
[![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/resource-disposer.svg?color=blue)](https://plugins.jenkins.io/resource-disposer)

![](/docs/images/resource-disposer.png)

Resource Disposer is a utility plugin for other plugins to depend on.
Resources that are no longer needed (VMs to delete, entries in other
systems, etc.) are registered in the plugin that attempts to delete it
repeatedly. Failures to do so will be reported in the form of
administrative monitor and can be examined at
`JENKINS_URL/administrativeMonitor/AsyncResourceDisposer/`. Such entries
are persisted between restarts and tracked until the plugin that has
contributed them either succeeds in disposing them or they get removed
some other way (ex: Administrator removes them manually). Provided the
problem preventing the disposal persists, instance administrators are
expected to resolve that based on administrative monitor reports.

 

See also [plugin developer documentation](/README.md).
