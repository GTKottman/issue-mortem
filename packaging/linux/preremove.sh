#!/bin/sh
systemctl --global disable issue-mortem-helper.service >/dev/null 2>&1 || true
