# Integrating Resource Disposer into plugin

## Overview

The plugin defines a single point of entry (`AsyncResourceDisposer.get().dispose(...)`)
for plugins to get resources deleted asynchronously. Plugin tracks the resources
and attempts to release them periodically until it succeeds. The registered resource
is represented as a `Disposable`, a named wrapper that knows how the resource should
be disposed (`Disposable#dispose()`). The method is expected to return either
confirmation the resource is gone, reason it has failed to delete it or, for mere
convenience, throw an exception that will be captured as such reason. The implementation
is expected to identify the resource (and its kind/source) as well as the problem
that occurred for instance admin to understand. `Disposable` implementations equal
to each other will be collapsed presuming they represent the same resource.  

## Manual intervention

Keep in mind the resource controlled by the plugin might get freed by a human or
some other automation. Therefore, the disposable algorithm need to be implemented in
a way to report successful deletion in case the resource disappears.

## Serialization

`Disposable`s are persisted between restarts so it needs to deserialize
in a way to still be able to perform its task. In case the resource is freed by
Jenkins restart naturally, it should deserialize into an object to report success
all the time (to be unregistered on first periodic attempt to dispose).

# Further Information
See [user documentation and more](https://plugins.jenkins.io/resource-disposer/) on the Jenkins Plugin Site.

[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/resource-disposer.svg?color=blue)](https://plugins.jenkins.io/resource-disposer)
[![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/resource-disposer.svg?color=blue)](https://plugins.jenkins.io/resource-disposer)
