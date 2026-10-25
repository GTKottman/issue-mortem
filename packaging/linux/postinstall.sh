#!/bin/sh
# Start the helper at every login, for every user. It starts now when someone opens "Issue Mortem Helper" from the
# app menu, or at the next login.
systemctl --global enable issue-mortem-helper.service >/dev/null 2>&1 || true
