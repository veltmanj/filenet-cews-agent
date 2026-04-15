# Request Capture Agent

This project builds a low-overhead Java instrumentation jar for observing inbound servlet traffic, with a focus on SOAP-style request paths such as IBM FileNet CEWS on WebSphere.

## What it captures

- Request start and completion events
- HTTP method, URI, query string, remote address
- Selected HTTP headers with redaction for sensitive values
- A bounded preview of the request body for text-like content types
- Total bytes observed on the servlet input stream
- Request duration and any exception seen by the servlet entrypoint

## Design goals

- No application code changes
- Async file writing to avoid blocking request threads
- Configurable URI filtering and body capture limits
- Conservative defaults to reduce performance impact

## Releases

- Latest release: [filenet-cews-agent releases](https://github.com/veltmanj/filenet-cews-agent/releases/latest)

## GitHub Packages

The Maven coordinates for the published package are:

```text
nl.nextend.filenet.cews:filenet-cews-agent:0.1.3
```

To publish future versions to GitHub Packages with the permanent Maven configuration now in `pom.xml`, add a `github` server entry to your Maven settings and run `deploy`.

Example `~/.m2/settings.xml` entry:

```xml
<servers>
<server>
<id>github</id>
<username>YOUR_GITHUB_USERNAME</username>
<password>YOUR_GITHUB_TOKEN</password>
</server>
</servers>
```

Then publish with:

```bash
./mvnw -q -DskipTests deploy
```

To consume the package from another Maven project, add the GitHub Packages repository and the dependency.

```xml
<repositories>
<repository>
<id>github-filenet-cews-agent</id>
<url>https://maven.pkg.github.com/veltmanj/filenet-cews-agent</url>
</repository>
</repositories>
```

```xml
<dependency>
<groupId>nl.nextend.filenet.cews</groupId>
<artifactId>filenet-cews-agent</artifactId>
<version>0.1.3</version>
</dependency>
```

## Build

```bash
./mvnw -q test package
```

The wrapper is pinned to Maven 3.9.14, so the project builds consistently even if the local `mvn` on `PATH` is older. On first run, `./mvnw` downloads that Maven distribution.

The shaded agent jar is written to `target/filenet-cews-agent-0.1.3.jar`.

## Testing

Run the test suite with:

```bash
./mvnw -q test
```

The test suite is intentionally focused on the failure-prone areas of the agent:

- configuration parsing and CEWS profile defaults
- request lifecycle capture and JSON event generation
- body preview truncation and escaping
- header filtering and redaction
- async writer flushing, shutdown, and queue-pressure behavior
- Byte Buddy advice entrypoints and no-op behavior for non-HTTP traffic

One async writer test deliberately points the writer at a directory instead of a file to force the background worker into its failure path. That test is expected to emit a warning log during the build, and the warning is part of validating dropped-event accounting under writer failure.

### Performance Harness

The repository also contains a manual performance harness for estimating the in-process cost of the capture path before rollout. It is intentionally not enabled in the default test run because the goal is to print measurements for a chosen load profile, not to enforce brittle timing thresholds.

Run it with:

```bash
./mvnw -q -DrunPerformanceTests=true -Dtest=PerformanceSimulationTest test
```

Useful system properties:

- `perfUsers`: logical user count to simulate. Default: `800`
- `perfRequestsPerUser`: number of requests per simulated user. Default: `5`
- `perfWorkerThreads`: worker thread pool size used by the harness. Default: `min(users, max(16, cpu*4))`
- `perfBodyBytes`: request body size used in the simulation. Default: `4096`
- `perfQueueCapacity`: async writer queue capacity used for measured scenarios. Default: `4096`
- `perfBaselineMillis`: `|` separated example request durations used for estimated end-to-end overhead tables. Default: `5|10|50`
- `perfReportDir`: directory where the harness writes the generated report files. Default: `target/performance-reports`

The harness reports a control run, a low-overhead run, a headers-only capture run, and a body-preview capture run. For each scenario it prints total requests, throughput, average latency, `p95`, `p99`, approximate heap delta, output size, and dropped-event count.

The report also estimates additive overhead against configurable example request baselines such as `5 ms`, `10 ms`, and `50 ms`. Those percentages are based on the incremental average latency above the harness control path, which makes the output more decision-useful than comparing raw throughput against a trivial checksum loop.

It also writes two report artifacts:

- `target/performance-reports/performance-report-YYYYMMDD-HHMMSS.md`: a timestamped Markdown summary with comparison tables, ASCII bar charts, a recent-runs trend table, a best-observed configuration callout, scenario guidance, and a recommended next-run command
- `target/performance-reports/performance-report-YYYYMMDD-HHMMSS.html`: a timestamped self-contained visual dashboard with scenario cards, delta columns, comparison bars, a recent-runs trend table, a best-observed configuration callout, scenario guidance, and a recommended next-run command
- `target/performance-reports/performance-report-latest.md`: the latest Markdown alias for quick access
- `target/performance-reports/performance-report-latest.html`: the latest HTML alias for quick access

This is still an approximation. It measures the core agent path in-process and is useful for comparing configurations before production, but it does not replace a full staging load test on WebSphere with real CEWS traffic.

## Attach to a JVM

```bash
-javaagent:/path/to/filenet-cews-agent-0.1.3.jar=output=/var/log/request-capture.ndjson,includeUri=.*/wsi/.*,maxBodyBytes=4096
```

Recommended FileNet CEWS low-overhead production candidate profile:

```bash
-javaagent:/path/to/filenet-cews-agent-0.1.3.jar=profile=filenet-cews-low-overhead,output=/var/log/cews-capture.ndjson
```

Alternate FileNet CEWS headers-only diagnostic profile:

```bash
-javaagent:/path/to/filenet-cews-agent-0.1.3.jar=profile=filenet-cews,output=/var/log/cews-capture.ndjson
```

Recommended FileNet CEWS targeted body preview profile:

```bash
-javaagent:/path/to/filenet-cews-agent-0.1.3.jar=profile=filenet-cews,output=/var/log/cews-capture.ndjson,captureBody=true,maxBodyBytes=2048
```

## Recommended WebSphere Settings

Add one of the following strings to the WebSphere JVM Generic JVM arguments for the target server.

Best throughput / lowest overhead:

```bash
-javaagent:/path/to/filenet-cews-agent-0.1.3.jar=profile=filenet-cews-low-overhead,output=/var/log/cews-capture.ndjson
```

Balanced throughput versus information gathering:

```bash
-javaagent:/path/to/filenet-cews-agent-0.1.3.jar=profile=filenet-cews,output=/var/log/cews-capture.ndjson
```

What each one gathers:

| Setting                                      | Event shape          | Request method + URI | Query string | Remote address | Selected headers | Content type | Content length | Response status | Duration | Error type/message | Body preview  |
| -------------------------------------------- | -------------------- | -------------------- | ------------ | -------------- | ---------------- | ------------ | -------------- | --------------- | -------- | ------------------ | ------------- |
| Best throughput: `filenet-cews-low-overhead` | End event only       | Yes                  | No           | No             | No               | Yes          | No             | Yes             | Yes      | Yes                | No            |
| Balanced: `filenet-cews`                     | Start and end events | Yes                  | Yes          | Yes            | Yes              | Yes          | Yes            | Yes             | Yes      | Yes                | No by default |

Practical guidance:

- Use `filenet-cews-low-overhead` for the first production rollout when the priority is keeping overhead as low as possible.
- Use `filenet-cews` when the lighter profile is not enough and you need headers plus request origin details for RCA.
- Turn on body preview only for a short, targeted capture window on one failing CEWS endpoint.

Windows WebSphere example:

```text
-javaagent:F:\path\to\filenet-cews-agent-0.1.3.jar=profile=filenet-cews-low-overhead,output=F:/var/log/cews-capture.ndjson
```

Windows syntax notes:

- Keep the jar path after `-javaagent:` as a normal Windows absolute path.
- Prefer a jar path without spaces if possible.
- The `output=` value can safely use forward slashes on Windows.
- Make sure the WebSphere service account can read the jar and write the output file path.

### wsadmin Automation

The repository also includes wsadmin helper scripts for adding or removing the agent from a WebSphere server without editing Generic JVM arguments manually.

The examples below assume a standalone full-profile WebSphere server using a local profile, not Network Deployment.

Shared wsadmin Jython script:

- `scripts/wsadmin/configure-javaagent.py`

Linux wrappers:

- `scripts/wsadmin/add-javaagent-linux.sh`
- `scripts/wsadmin/remove-javaagent-linux.sh`

Windows wrappers:

- `scripts/wsadmin/add-javaagent-windows.bat`
- `scripts/wsadmin/remove-javaagent-windows.bat`

Linux add example:

```bash
WSADMIN_CMD=/opt/IBM/WebSphere/AppServer/profiles/<Profile>/bin/wsadmin.sh \
./scripts/wsadmin/add-javaagent-linux.sh MyCell MyNode server1 /opt/filenet-cews-agent
```

Linux remove example:

```bash
WSADMIN_CMD=/opt/IBM/WebSphere/AppServer/profiles/<Profile>/bin/wsadmin.sh \
./scripts/wsadmin/remove-javaagent-linux.sh MyCell MyNode server1 /opt/filenet-cews-agent
```

Windows add example:

```bat
set WSADMIN_CMD=C:\IBM\WebSphere\AppServer\profiles\<Profile>\bin\wsadmin.bat
scripts\wsadmin\add-javaagent-windows.bat MyCell MyNode server1 F:\IBM\Agent\filenet-cews-agent
```

Windows remove example:

```bat
set WSADMIN_CMD=C:\IBM\WebSphere\AppServer\profiles\<Profile>\bin\wsadmin.bat
scripts\wsadmin\remove-javaagent-windows.bat MyCell MyNode server1 F:\IBM\Agent\filenet-cews-agent
```

If you later run the same scripts against Network Deployment, keep `WSADMIN_CMD` pointed at the deployment-manager profile and set `WSADMIN_ARGS` with the SOAP connection parameters.

Wrapper defaults:

- Linux defaults `agent_dir` to `/opt/filenet-cews-agent`
- Windows defaults `agent_dir` to `C:\IBM\Agent\filenet-cews-agent`
- The add wrappers apply `profile=filenet-cews-low-overhead` and write output to `cews-capture.ndjson` in the same directory as the jar
- The remove wrappers remove the javaagent entry for `filenet-cews-agent-0.1.3.jar`

After running add or remove, restart the target WebSphere server so the JVM arguments take effect.

## Agent arguments

Arguments are comma-separated key/value pairs:

- `profile`: named preset. Currently supports `filenet-cews` and `filenet-cews-low-overhead`.
- `eventMode`: `start-and-end` or `end-only`
- `metadataMode`: `full` or `light`
- `output`: destination file for newline-delimited JSON events
- `includeUri`: regex used to filter requests by URI
- `captureBody`: `true` or `false`
- `maxBodyBytes`: maximum number of request-body bytes to retain per request
- `queueCapacity`: async writer queue size
- `sampleRate`: capture 1 out of N matching requests
- `diagnosticTransforms`: `true` or `false`. Defaults to `true` in `0.1.3` so startup writes `agent-transform` events for each transformed servlet/filter/input-stream class; set it to `false` after diagnostics are no longer needed.
- `includeHeaders`: `|` separated list of headers to include. Empty means include all.
- `redactHeaders`: `|` separated list of headers to redact
- `bodyContentTypes`: `|` separated list of content types allowed for body preview. Supports exact match, prefix match via `text/*`, and suffix wildcards like `*/xml`.

Example:

```text
output=/var/log/cews-capture.ndjson,includeUri=.*/FNCEWS.*,captureBody=true,maxBodyBytes=8192,includeHeaders=Host|SOAPAction|Content-Type|Content-Length|User-Agent|X-Request-ID,redactHeaders=Authorization|Cookie|Set-Cookie,sampleRate=1
```

## FileNet CEWS profile

The `filenet-cews` profile is the fuller diagnostic profile when you need more request metadata and can tolerate more overhead during a short RCA window.

- URI filter defaults to `.*\/(?:wsi\/)?FNCEWS(?:40(?:MTOM|DIME))?.*`
- Body capture stays off by default
- `maxBodyBytes` defaults to `2048` when body capture is later enabled
- Header allowlist is narrowed to CEWS-relevant transport and SOAP routing headers
- Redaction also covers `Proxy-Authorization`

Explicit arguments always win over profile defaults, so you can safely layer `captureBody=true`, a custom `includeUri`, or different header lists on top of the preset.

## FileNet CEWS low-overhead profile

The `filenet-cews-low-overhead` profile is the primary production candidate when your acceptable budget is closer to single-digit percentage overhead on real request latency.

- Uses the same CEWS URI filter as `filenet-cews`
- Emits only the end event by default
- Switches to `metadataMode=light`, which omits headers, query string, remote address, and content length from serialized events
- Keeps body capture off by default and lowers `maxBodyBytes` to `512` for any later targeted override

Start with this profile for any production trial. Move to `filenet-cews` only if the low-overhead output is insufficient for the RCA question you are trying to answer.

Explicit arguments still win over the profile defaults, so you can override `eventMode`, `metadataMode`, or `captureBody` for a short troubleshooting window.

## Notes for WebSphere / FileNet RCA

- Start with `profile=filenet-cews-low-overhead` and a narrow `includeUri` filter.
- Enable body preview only for the failing CEWS endpoint and keep `maxBodyBytes` small.
- Use `profile=filenet-cews` only for short, targeted diagnostic windows when the lighter profile is not enough.
- The agent logs a start event immediately so requests that later stall can still be correlated.
- This project targets `javax.servlet` based containers, which matches WebSphere 9.x.
