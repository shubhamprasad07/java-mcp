# Weather MCP Server (Java)

MCP server that exposes **get weather forecast** and **get alerts** tools using the [US National Weather Service API](https://api.weather.gov). Implemented with **Spring Boot** and **Spring AI MCP** as in the [official Build an MCP server (Java)](https://modelcontextprotocol.io/docs/develop/build-server#java) guide.

## Requirements

- **Java 17+**
- **Maven 3.6+** (or use the wrapper)
- Maven must resolve `org.springframework.boot:spring-boot-starter-parent` and `org.springframework.ai:spring-ai-bom` from Maven Central or Spring repos. If your `settings.xml` uses a **mirror** (e.g. a corporate repo) that doesn’t have these, the build may fail with “Non-resolvable parent POM”. In that case, either add Central to your mirror list or run without that mirror (e.g. a separate `settings.xml` or `-D` overrides) so the parent and Spring AI BOM can be resolved.

## Build

If your global Maven `settings.xml` uses a mirror (e.g. corporate Nexus or paig-m2) that doesn’t have Spring artifacts, use the project’s standalone settings so the pom’s repositories (Central + Spring) are used:

```bash
mvn -s settings-standalone.xml clean package
```

Otherwise:

```bash
mvn clean package
```

JAR output: `target/weather-mcp-server-1.0.0.jar`.

## Config (Claude Desktop)

Add to `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS):

```json
{
  "mcpServers": {
    "weather-java": {
      "command": "java",
      "args": [
        "-Dspring.ai.mcp.server.stdio=true",
        "-jar",
        "/ABSOLUTE/PATH/TO/weather-server-java/target/weather-mcp-server-1.0.0.jar"
      ]
    }
  }
}
```

Use the real path to your built JAR. Then fully quit and reopen Claude Desktop.

## Config (Cursor)

In `.cursor/mcp.json` (or Cursor MCP settings), add:

```json
{
  "mcpServers": {
    "weather-java": {
      "command": "java",
      "args": [
        "-Dspring.ai.mcp.server.stdio=true",
        "-jar",
        "/ABSOLUTE/PATH/TO/weather-server-java/target/weather-mcp-server-1.0.0.jar"
      ]
    }
  }
}
```

## Tools

| Tool | Description |
|------|-------------|
| `getWeatherForecastByLocation` | Forecast for a latitude/longitude (US only). |
| `getAlerts` | Active weather alerts for a two-letter US state code (e.g. CA, NY). |

## Project layout

- **`WeatherService`** – `@Service` with `@Tool` methods; uses Spring `RestClient` to call NWS.
- **`McpServerApplication`** – `@SpringBootApplication` and `ToolCallbackProvider` bean that registers `WeatherService` tools with the MCP server.
- **`application.properties`** – `spring.ai.mcp.server.stdio=true`, no banner.
- **`logback-spring.xml`** – Sends all console logging to **stderr** so **stdout** stays clean for MCP JSON-RPC (required for Cursor/Claude).

## Troubleshooting

**`NoSuchFieldError: POJO` or `JsonProperty.isRequired()` / Jackson conflict**  
This project uses **Spring AI 1.1.1** (not 2.0.x) so the app runs with Jackson 2 only. Spring AI 2.0 pulls in Jackson 3 (`tools.jackson`), which clashes with Spring Boot’s Jackson 2 and causes runtime errors. Stick with the 1.1.1 BOM version in the POM.

**Cursor/Claude shows "Client closed" or the server disconnects immediately**  
MCP over STDIO uses stdout for protocol messages. If the JVM writes anything to stdout (e.g. default Logback console appender, or a banner), the client sees invalid data and closes. This project uses `logback-spring.xml` to force all console logging to stderr. Rebuild after changing logging config: `mvn -s settings-standalone.xml package`.


## Run (STDIO)

For Claude Desktop, Cursor, or any MCP host that talks over stdio:

```bash
java -Dspring.ai.mcp.server.stdio=true -jar target/weather-mcp-server-1.0.0.jar
```

Or with an explicit config file:

```bash
java -Dspring.ai.mcp.server.stdio=true -jar target/weather-mcp-server-1.0.0.jar
```

## Reference

- [Build an MCP server – Java](https://modelcontextprotocol.io/docs/develop/build-server#java)
- [Spring AI MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
- [Spring AI Weather STDIO example](https://github.com/spring-projects/spring-ai-examples/tree/main/model-context-protocol/weather/starter-stdio-server)
