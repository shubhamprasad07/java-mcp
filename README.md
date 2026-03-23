# Weather MCP Server (Java)

MCP server that exposes **get weather forecast** and **get alerts** tools using the [US National Weather Service API](https://api.weather.gov). Implemented with **Spring Boot**, **Spring AI MCP**, and **Streamable HTTP** (Tomcat) so clients connect via URL.

## Requirements

- **Java 17+**
- **Maven 3.6+**
- Maven must resolve `org.springframework.boot:spring-boot-starter-parent` and `org.springframework.ai:spring-ai-bom` from Maven Central or Spring repos. If your `settings.xml` uses a **mirror** (e.g. a corporate repo) that doesn’t have these, the build may fail. Use `settings-standalone.xml` for this project (see Build).

## Build

If your global Maven `settings.xml` uses a mirror (e.g. corporate Nexus or paig-m2) that doesn’t have Spring artifacts, use the project’s standalone settings:

```bash
mvn -s settings-standalone.xml clean package
```

Otherwise:

```bash
mvn clean package
```

JAR output: `target/weather-mcp-server-1.0.0.jar`.

## Run

Start the server with Maven (loads `application.properties`; default port **9090**, Streamable MCP on **`/`**):

```bash
mvn spring-boot:run
```

If your global `settings.xml` uses a corporate mirror that is unreachable or missing Spring Boot plugins, use the project settings file:

```bash
mvn -s settings-standalone.xml spring-boot:run
```

Change the port via `server.port` in `application.properties`, or for a one-off:

```bash
mvn -s settings-standalone.xml spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8080"
```

## Client config

Point your MCP client at the **base URL** of the server (POST goes to that path; this project maps the Streamable endpoint to **`/`**).

### Cursor

In `.cursor/mcp.json` (or Cursor MCP settings):

```json
{
  "mcpServers": {
    "weather-java": {
      "url": "http://localhost:9090"
    }
  }
}
```

If you remove `spring.ai.mcp.server.streamable-http.mcp-endpoint=/` from `application.properties` (Spring AI default), use:

```json
"url": "http://localhost:9090/mcp"
```

### Claude Desktop (and other clients)

Use whatever entry your app provides for **remote / HTTP** MCP servers, with the same URL as above (e.g. `http://localhost:9090` or `http://localhost:9090/mcp` depending on `mcp-endpoint`).

## Tools

| Tool | Description |
|------|-------------|
| `getWeatherForecastByLocation` | Forecast for a latitude/longitude (US only). |
| `getAlerts` | Active weather alerts for a two-letter US state code (e.g. CA, NY). |

## Project layout

- **`WeatherService`** – `@Service` with `@Tool` methods; uses Spring `RestClient` to call NWS.
- **`McpServerApplication`** – `@SpringBootApplication` and `ToolCallbackProvider` bean that registers `WeatherService` tools with the MCP server.
- **`application.properties`** – `server.port`, `spring.ai.mcp.server.protocol=STREAMABLE`, `spring.ai.mcp.server.stdio=false`, Streamable path, no banner.
- **`logback-spring.xml`** – Console logging to **stderr** (keeps default console behavior tidy for HTTP as well).

## Troubleshooting

**`No plugin found for prefix 'spring-boot'` or downloads fail via corporate mirror**  
Use: `mvn -s settings-standalone.xml spring-boot:run` or `mvn -s settings-standalone.xml clean package`. Add `-U` once if resolution was cached as failed.

**404 on `/` when using a client `url`**  
The Streamable MCP path must match what the client calls. This repo sets `spring.ai.mcp.server.streamable-http.mcp-endpoint=/` so `http://localhost:9090` works. If you use the default `/mcp`, set the client URL to `http://localhost:9090/mcp`.

**`NoSuchFieldError: POJO` or Jackson conflicts**  
Stay on **Spring AI 1.1.1** (see `pom.xml`); do not move this project to Spring AI 2.0.x without addressing Jackson 2 vs 3.

## Reference

- [Build an MCP server – Java](https://modelcontextprotocol.io/docs/develop/build-server#java)
- [Spring AI MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
- [Spring AI Streamable HTTP server](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html)
- [Spring AI weather example (WebFlux)](https://github.com/spring-projects/spring-ai-examples/tree/main/model-context-protocol/weather/starter-webflux-server)
