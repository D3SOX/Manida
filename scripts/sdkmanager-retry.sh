#!/usr/bin/env bash
# Hosted runners sometimes download a corrupt SDK archive, so retry the install before failing the job.
set -u
for attempt in 1 2 3; do
    sdkmanager "$@" && exit 0
    echo "::warning title=Android SDK install failed::Attempt $attempt of 3 failed for: $*"
    sleep $((attempt * 15))
done
exit 1
