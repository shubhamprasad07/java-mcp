# Code Explanation, What Went Wrong, and Why `settings-standalone.xml`

## 1. What the code does

### 1.1 `pom.xml`

- **Parent:** `spring-boot-starter-parent` 3.4.3 — provides Spring Boot, plugin defaults, and dependency versions.
- **Spring AI:** `spring-ai-bom` 1.1.1 is imported so we get a consistent set of Spring AI versions. We use **1.1.1** (not 2.0.x) to avoid mixing Jackson 2 and Jackson 3 (see “What went wrong” below).
- **Repositories:** `central` and `spring-milestones` so Maven can resolve Spring Boot and Spring AI. These are only used when **no mirror** is active (see “Why settings-standalone.xml”).
- **Dependencies:**
  - `spring-ai-starter-mcp-server` — MCP server over STDIO, tool conversion, protocol handling.
  - `spring-boot-starter-web` — brings in `RestClient` and the web stack (we only use RestClient; the embedded Tomcat is unused when running in STDIO mode).

### 1.2 `McpServerApplication.java`

- **`@SpringBootApplication`** — Enables component scanning and auto-configuration; starts the app.
- **`main`** — Runs the Spring context. With `spring.ai.mcp.server.stdio=true`, the MCP starter starts a **STDIO transport**: it reads JSON-RPC from **stdin** and writes responses to **stdout**. The process must keep running and not write anything else to stdout.
- **`ToolCallbackProvider` bean** — Spring AI’s MCP layer looks for beans of type `ToolCallbackProvider`. This bean is built with `MethodToolCallbackProvider.builder().toolObjects(weatherService).build()`, which:
  - Scans `WeatherService` for methods annotated with `@Tool`,
  - Turns each into an MCP “tool” (name, description, input schema from `@ToolParam`),
  - Registers them with the MCP server so Cursor/Claude can list and call them.

So: the app is a normal Spring Boot app that also runs an MCP server on stdio and exposes `WeatherService`’s `@Tool` methods as MCP tools.

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

- **`spring.ai.mcp.server.stdio=true`** — Tells the Spring AI MCP starter to use **STDIO** transport (stdin/stdout) instead of HTTP. Required for Cursor/Claude, which launch the JAR and talk over pipes.
- **`spring.main.banner-mode=off`** — Prevents the Spring banner from being printed. Any print to stdout would mix with MCP JSON-RPC and break the client.
- **`logging.pattern.console=`** — Reduces console log format. The actual **destination** of logs (stderr) is set in Logback (see below).

### 1.5 `logback-spring.xml`

- By default, Logback’s **ConsoleAppender** writes to **stdout**. For MCP over STDIO, stdout must carry **only** JSON-RPC.
- This file defines a **ConsoleAppender** with **`<target>System.err</target>`** and attaches it to the root logger. So all normal logging goes to **stderr**, and stdout stays clean for the protocol.
- Without this, startup logs (and any `log.info(...)`) would go to stdout, the client would see invalid JSON, and it would close the connection (“Client closed”).

---

## 2. What went wrong (and why)

### 2.1 Build: “Non-resolvable parent POM” / “Unknown host paig-m2.ops.paig.ai”

- **What happened:** `mvn clean package` tried to resolve `spring-boot-starter-parent` (and other artifacts) from a **mirror** defined in your **global** `~/.m2/settings.xml` (e.g. `paig-dev-dev1` → `https://paig-m2.ops.paig.ai/...`). That host was unreachable or doesn’t have Spring artifacts.
- **Why:** In Maven, a **mirror** applies to **all** repository requests. So even though the **pom.xml** declares `central` and `spring-milestones`, Maven sends those requests to the mirror first. If the mirror fails or doesn’t have the artifact, the build fails. Repositories in the POM are not used for requests that are already redirected by a mirror.
- **Fix:** Use a **different** settings file that has **no** `<mirrors>` section when building this project: `mvn -s settings-standalone.xml clean package`. With no mirror, Maven uses the repositories from the POM (Central + Spring Milestones), and the build can succeed.

### 2.2 Runtime: “Client closed” right after starting

- **What happened:** Cursor started the JAR, then immediately saw “Client closed.”
- **Why:** The MCP protocol uses **stdout** for JSON-RPC. Anything else written to stdout (e.g. Spring’s default Logback console appender, or the Spring banner) corrupts the stream. The client then sees non-JSON or malformed data and closes the connection.
- **Fix:** We added **`logback-spring.xml`** so that all console logging goes to **stderr** only, and we set **`spring.main.banner-mode=off`**. After that, stdout is reserved for MCP and the client stays connected.

### 2.3 Runtime: `NoSuchMethodError: JsonProperty.isRequired()`

- **What happened:** After fixing logging, the app failed during startup with a `NoSuchMethodError` on `com.fasterxml.jackson.annotation.JsonProperty.isRequired()`.
- **Why:** We had been using **Spring AI 2.0.0-SNAPSHOT**, which pulls in **Jackson 3** (`tools.jackson.core:jackson-databind`). Jackson 3’s code expects `JsonProperty.isRequired()`, which was added in **Jackson 2.19**. Spring Boot was bringing **Jackson 2.18.2**, so the annotations JAR on the classpath didn’t have that method → `NoSuchMethodError`.
- **Fix (attempted):** We tried overriding `jackson-annotations` to 2.19.0 so the method existed. That fixed this error but led to the next one.

### 2.4 Runtime: `NoSuchFieldError: POJO`

- **What happened:** After the Jackson annotations override, startup failed with `NoSuchFieldError: POJO` inside Jackson’s `DeserializerCache`.
- **Why:** We still had **two Jackson stacks** on the classpath: **Jackson 3** (`tools.jackson.*`, from Spring AI 2.0) and **Jackson 2** (`com.fasterxml.jackson.*`, from Spring Boot). They are not compatible: enums and internal constants (like `POJO`) don’t match. So at runtime, Jackson 3 code was loading a class or enum from Jackson 2 (or vice versa), causing the `NoSuchFieldError`.
- **Fix:** Avoid mixing the two. We switched the project to **Spring AI 1.1.1**, which uses **only Jackson 2**. So the entire app runs on Jackson 2 (Spring Boot + Spring AI 1.1.1), and we removed the `jackson-annotations` override and the 2.0.0-SNAPSHOT / spring-snapshots usage.

---

## 3. Why we use `settings-standalone.xml` in `mvn -s settings-standalone.xml clean package`

- **What it is:** A **minimal** Maven settings file in the project that **does not** define any `<mirrors>`.
- **What Maven does with it:** The `-s` option tells Maven to use **this** file instead of `~/.m2/settings.xml` for this run. So for this build, no mirror is active.
- **Effect:** Without a mirror, Maven uses the **repositories declared in the POM**: `central` (https://repo1.maven.org/maven2) and `spring-milestones` (https://repo.spring.io/milestone). So it can resolve:
  - `org.springframework.boot:spring-boot-starter-parent`
  - `org.springframework.ai:spring-ai-bom` and the Spring AI starters
  even if your corporate or personal mirror doesn’t have them or is unreachable.
- **When to use it:** Whenever you build **this** project and your global `settings.xml` has a mirror that doesn’t include Spring/central or that you can’t reach. So the standard build command for this repo is:
  ```bash
  mvn -s settings-standalone.xml clean package
  ```
- **What we don’t change:** We don’t modify your global `~/.m2/settings.xml`. Other projects can keep using the same mirror; only this project uses the standalone settings when you pass `-s settings-standalone.xml`.

---

## 4. Quick reference

| Item | Purpose |
|------|--------|
| **pom.xml** | Spring Boot parent, Spring AI 1.1.1 BOM, MCP + web starters; repos = central + spring-milestones. |
| **McpServerApplication** | Starts Spring Boot; defines `ToolCallbackProvider` bean that registers `WeatherService`’s `@Tool` methods as MCP tools. |
| **WeatherService** | NWS RestClient; `@Tool` methods `getWeatherForecastByLocation` and `getAlerts`; records for NWS JSON. |
| **application.properties** | `spring.ai.mcp.server.stdio=true`, no banner, minimal console pattern. |
| **logback-spring.xml** | Send all console logging to **stderr** so **stdout** is only MCP JSON-RPC. |
| **settings-standalone.xml** | No mirror; used with `-s` so this project builds using POM repositories (Central + Spring) when global mirror is wrong or unreachable. |
