# Code Explanation, What Went Wrong, and Why `settings-standalone.xml`

## 1. What the code does

### 1.1 `pom.xml`

- **Parent:** `spring-boot-starter-parent` 3.4.3 — provides Spring Boot, plugin defaults, and dependency versions.
- **Spring AI:** `spring-ai-bom` 1.1.1 is imported so we get a consistent set of Spring AI versions. We use **1.1.1** (not 2.0.x) to avoid mixing Jackson 2 and Jackson 3 (see “What went wrong” below).
- **Repositories:** `central` and `spring-milestones` so Maven can resolve Spring Boot and Spring AI. These are only used when **no mirror** is active (see “Why settings-standalone.xml”).
- **Dependencies:**
  - `spring-ai-starter-mcp-server` — MCP server core, tool conversion, protocol support.
  - `spring-ai-starter-mcp-server-webmvc` — Streamable HTTP MCP on Spring MVC / Tomcat (with exclusions so Boot versions stay aligned with the parent BOM).
  - `spring-boot-starter-web` — embedded Tomcat for MCP HTTP and `RestClient` for NWS.

### 1.2 `McpServerApplication.java`

- **`@SpringBootApplication`** — Enables component scanning and auto-configuration; starts the app.
- **`main`** — Runs the Spring context. With `spring.ai.mcp.server.protocol=STREAMABLE` and `spring.ai.mcp.server.stdio=false`, Spring AI exposes MCP over **HTTP** on the configured path (see `application.properties`).
- **`ToolCallbackProvider` bean** — Spring AI’s MCP layer registers `ToolCallback` beans from `MethodToolCallbackProvider.builder().toolObjects(weatherService).build()`, which maps `WeatherService` `@Tool` methods to MCP tools.

So: the app is a Spring Boot service with Tomcat and an MCP Streamable HTTP endpoint, exposing `WeatherService`’s `@Tool` methods.

### 1.3 `WeatherService.java`

- **`@Service`** — Spring manages a single instance and injects it where needed (e.g. into the `ToolCallbackProvider` bean).
- **`RestClient`** — Single HTTP client for `https://api.weather.gov` with headers required by NWS (`Accept: application/geo+json`, `User-Agent`).
- **Records** (`Points`, `Forecast`, `Period`, `Alert`, …) — Map NWS JSON to Java types. `@JsonProperty` matches JSON field names (e.g. `areaDesc`); `@JsonIgnoreProperties(ignoreUnknown = true)` avoids errors when the API adds new fields.
- **`@Tool`** — Marks a method as an MCP tool. The `description` is shown to the LLM/client.
- **`@ToolParam`** — Describes each parameter for the tool’s input schema (name, type, description).
- **`getWeatherForecastByLocation(lat, lon)`** — Calls NWS `/points/{lat},{lon}` to get a forecast URL, then fetches that URL to get periods, then formats them as a string. Returns a clear message if the location isn’t supported (e.g. non-US) or the request fails.
- **`getAlerts(state)`** — Validates a two-letter state code, calls NWS `/alerts/active/area/{state}`, and formats each alert’s properties as text. Handles null/empty safely with `nullToEmpty`.

So: this class is both the NWS client and the implementation of the two MCP tools.

### 1.4 `application.properties`

- **`spring.ai.mcp.server.stdio=false`** — HTTP transport, not stdin/stdout.
- **`spring.ai.mcp.server.protocol=STREAMABLE`** — Streamable HTTP MCP (Spring MVC).
- **`spring.ai.mcp.server.streamable-http.mcp-endpoint`** — MCP route (this project uses **`/`** so clients can use `http://host:port` as the URL; default in Spring AI is **`/mcp`**).
- **`server.port`** — Tomcat port (default **9090**).
- **`spring.main.banner-mode=off`** — Quieter startup logs.
- **`logging.pattern.console=`** — Minimal console pattern; Logback still routes console output to stderr (see below).

### 1.5 `logback-spring.xml`

- Console logging uses a **ConsoleAppender** with **`<target>System.err</target>`** so logs do not compete with normal use of stdout in tooling. For pure HTTP MCP, stdout is less critical than for older stdio-based setups, but stderr-only logging remains a safe default.

---

## 2. What went wrong (and why)

### 2.1 Build: “Non-resolvable parent POM” / “Unknown host paig-m2.ops.paig.ai”

- **What happened:** `mvn clean package` tried to resolve `spring-boot-starter-parent` (and other artifacts) from a **mirror** defined in your **global** `~/.m2/settings.xml` (e.g. `paig-dev-dev1` → `https://paig-m2.ops.paig.ai/...`). That host was unreachable or doesn’t have Spring artifacts.
- **Why:** In Maven, a **mirror** applies to **all** repository requests. So even though the **pom.xml** declares `central` and `spring-milestones`, Maven sends those requests to the mirror first. If the mirror fails or doesn’t have the artifact, the build fails.
- **Fix:** Use a **different** settings file that has **no** `<mirrors>` section when building this project: `mvn -s settings-standalone.xml clean package`.

### 2.2 Runtime: client gets **404** on `/` when using an MCP `url`

- **What happened:** The MCP client POSTs to the configured URL path, but no Streamable route was registered (e.g. stdio-only config, or URL path does not match `mcp-endpoint`).
- **Fix:** Use `spring.ai.mcp.server.protocol=STREAMABLE`, `spring.ai.mcp.server.stdio=false`, and `spring-ai-starter-mcp-server-webmvc` on the classpath. Align **client URL** with **`spring.ai.mcp.server.streamable-http.mcp-endpoint`** (`/` vs `/mcp`).

### 2.3 Runtime: `NoSuchMethodError: JsonProperty.isRequired()`

- **What happened:** Using **Spring AI 2.0.0-SNAPSHOT** pulled **Jackson 3** while Spring Boot brought **Jackson 2.18.x**, causing annotation/API mismatches.
- **Fix:** Use **Spring AI 1.1.1** with **only Jackson 2** (see `pom.xml`).

### 2.4 Runtime: `NoSuchFieldError: POJO`

- **What happened:** Two Jackson stacks (2.x and 3.x) on the classpath.
- **Fix:** Stay on **Spring AI 1.1.1** and a single Jackson line.

---

## 3. Why we use `settings-standalone.xml` in `mvn -s settings-standalone.xml clean package`

- **What it is:** A **minimal** Maven settings file in the project that **does not** define any `<mirrors>`.
- **What Maven does with it:** The `-s` option tells Maven to use **this** file instead of `~/.m2/settings.xml` for this run. So for this build, no mirror is active.
- **Effect:** Without a mirror, Maven uses the **repositories declared in the POM**: `central` and `spring-milestones`.
- **When to use it:** Whenever you build **this** project and your global `settings.xml` has a mirror that doesn’t include Spring/central or that you can’t reach.

---

## 4. Quick reference

| Item | Purpose |
|------|--------|
| **pom.xml** | Spring Boot parent, Spring AI 1.1.1 BOM, MCP + WebMVC starters; repos = central + spring-milestones. |
| **McpServerApplication** | Starts Spring Boot; defines `ToolCallbackProvider` for `WeatherService` tools. |
| **WeatherService** | NWS RestClient; `@Tool` methods `getWeatherForecastByLocation` and `getAlerts`; records for NWS JSON. |
| **application.properties** | Streamable HTTP MCP, `server.port`, `mcp-endpoint`, no banner. |
| **logback-spring.xml** | Console logging to **stderr**. |
| **settings-standalone.xml** | No mirror; used with `-s` when global mirror is wrong or unreachable. |
