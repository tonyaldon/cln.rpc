#!/usr/bin/env python3

from pyln.client import Plugin
import time

plugin = Plugin()


@plugin.method("send-message-notifications")
def send_message_notifications(plugin, request, **kwargs):
    request.notify("foo")
    request.notify("bar")
    request.notify("baz")
    return {"foo": "bar"}


@plugin.method("send-progress-notifications")
def send_progress_notifications(plugin, request, **kwargs):
    plugin.notify_progress(request, 0, 3)
    plugin.notify_progress(request, 1, 3)
    plugin.notify_progress(request, 2, 3)
    return {"foo": "bar"}


plugin.run()
