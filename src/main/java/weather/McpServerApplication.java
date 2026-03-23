package weather;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot MCP server exposing weather tools via STDIO.
 * Configure Claude/Cursor to run: java -Dspring.ai.mcp.server.stdio=true -jar weather-mcp-server.jar
 *
 * @see <a href="https://modelcontextprotocol.io/docs/develop/build-server#java">Build an MCP server (Java)</a>
 */
@SpringBootApplication
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider weatherTools(WeatherService weatherService) {
        return MethodToolCallbackProvider.builder().toolObjects(weatherService).build();
    }
}
