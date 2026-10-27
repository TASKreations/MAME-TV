#!/usr/bin/env bash
# Forward the managed environment's proxy and system CA trust to Java.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/workspace/toolchains/jdk-21.0.9+10}"
export ANDROID_HOME="${ANDROID_HOME:-/workspace/toolchains/android-sdk}"
python3 - "$@" <<'PY'
import os, sys
from urllib.parse import urlparse
args = ['./gradlew']
proxy = os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy')
if proxy:
    parsed = urlparse(proxy)
    if parsed.username or parsed.password:
        raise SystemExit('Use the managed credential-free proxy endpoint; do not put proxy credentials in Gradle arguments.')
    if not parsed.hostname:
        raise SystemExit('Invalid configured proxy endpoint')
    for scheme in ('http', 'https'):
        args += [f'-D{scheme}.proxyHost={parsed.hostname}', f'-D{scheme}.proxyPort={parsed.port or 80}']
    trust = '/etc/ssl/certs/java/cacerts'
    if os.path.isfile(trust):
        args += [f'-Djavax.net.ssl.trustStore={trust}']
os.execv('./gradlew', args + sys.argv[1:])
PY
