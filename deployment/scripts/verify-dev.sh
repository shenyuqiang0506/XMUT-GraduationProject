#!/bin/sh
set -eu
REPO_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$REPO_DIR"
set -a
. ./deployment/secrets/dev/backend.env
set +a
export XMUT_INFRA_TEST=true
exec ./GraduationProject/mvnw -s .mvn/settings.xml -B -ntp -pl GraduationProject -Dtest=InfrastructureIT test
